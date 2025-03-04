// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Inc.
package com.starrocks.sql.optimizer.statistics;

import avro.shaded.com.google.common.collect.ImmutableList;
import com.github.benmanes.caffeine.cache.AsyncCacheLoader;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.PrimitiveType;
import com.starrocks.catalog.Table;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.ErrorCode;
import com.starrocks.common.ErrorReport;
import com.starrocks.common.util.DateUtils;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.statistic.StatisticExecutor;
import com.starrocks.thrift.TStatisticData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

import static com.starrocks.sql.optimizer.Utils.getLongFromDateTime;

public class ColumnBasicStatsCacheLoader implements AsyncCacheLoader<ColumnStatsCacheKey, Optional<ColumnStatistic>> {
    private static final Logger LOG = LogManager.getLogger(ColumnBasicStatsCacheLoader.class);
    private final StatisticExecutor statisticExecutor = new StatisticExecutor();

    @Override
    public @NonNull CompletableFuture<Optional<ColumnStatistic>> asyncLoad(@NonNull ColumnStatsCacheKey cacheKey,
                                                                           @NonNull Executor executor) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<TStatisticData> statisticData = queryStatisticsData(cacheKey.tableId, cacheKey.column);
                // check TStatisticData is not empty, There may be no such column Statistics in BE
                if (!statisticData.isEmpty()) {
                    return Optional.of(convert2ColumnStatistics(statisticData.get(0)));
                } else {
                    return Optional.empty();
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Map<@NonNull ColumnStatsCacheKey, @NonNull Optional<ColumnStatistic>>> asyncLoadAll(
            @NonNull Iterable<? extends @NonNull ColumnStatsCacheKey> keys, @NonNull Executor executor) {
        return CompletableFuture.supplyAsync(() -> {

            try {
                long tableId = -1;
                List<String> columns = new ArrayList<>();
                for (ColumnStatsCacheKey key : keys) {
                    tableId = key.tableId;
                    columns.add(key.column);
                }
                List<TStatisticData> statisticData = queryStatisticsData(tableId, columns);
                Map<ColumnStatsCacheKey, Optional<ColumnStatistic>> result = new HashMap<>();
                // There may be no statistics for the column in BE
                // Complete the list of statistics information, otherwise the columns without statistics may be called repeatedly
                for (ColumnStatsCacheKey cacheKey : keys) {
                    result.put(cacheKey, Optional.empty());
                }

                for (TStatisticData data : statisticData) {
                    ColumnStatistic columnStatistic = convert2ColumnStatistics(data);
                    result.put(new ColumnStatsCacheKey(data.tableId, data.columnName),
                            Optional.of(columnStatistic));
                }
                return result;
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Optional<ColumnStatistic>> asyncReload(
            @NonNull ColumnStatsCacheKey key, @NonNull Optional<ColumnStatistic> oldValue,
            @NonNull Executor executor) {
        return asyncLoad(key, executor);
    }

    private List<TStatisticData> queryStatisticsData(long tableId, String column) throws Exception {
        return queryStatisticsData(tableId, ImmutableList.of(column));
    }

    private List<TStatisticData> queryStatisticsData(long tableId, List<String> columns) throws Exception {
        return statisticExecutor.queryStatisticSync(null, tableId, columns);
    }

    private ColumnStatistic convert2ColumnStatistics(TStatisticData statisticData) throws AnalysisException {
        Database db = GlobalStateMgr.getCurrentState().getDb(statisticData.dbId);
        if (db == null) {
            ErrorReport.reportAnalysisException(ErrorCode.ERR_BAD_DB_ERROR, statisticData.dbId);
        }
        Table table = db.getTable(statisticData.tableId);
        if (!(table instanceof OlapTable)) {
            ErrorReport.reportAnalysisException(ErrorCode.ERR_BAD_TABLE_ERROR, statisticData.tableId);
        }
        Column column = table.getColumn(statisticData.columnName);
        if (column == null) {
            ErrorReport.reportAnalysisException(ErrorCode.ERR_BAD_FIELD_ERROR, statisticData.columnName);
        }

        ColumnStatistic.Builder builder = ColumnStatistic.builder();
        double minValue = Double.NEGATIVE_INFINITY;
        double maxValue = Double.POSITIVE_INFINITY;
        try {
            if (column.getPrimitiveType().isCharFamily()) {
                // do nothing
            } else if (column.getPrimitiveType().equals(PrimitiveType.DATE)) {
                if (statisticData.isSetMin() && !statisticData.getMin().isEmpty()) {
                    minValue = (double) getLongFromDateTime(DateUtils.parseStringWithDefaultHSM(
                            statisticData.min, DateUtils.DATE_FORMATTER_UNIX));
                }
                if (statisticData.isSetMax() && !statisticData.getMax().isEmpty()) {
                    maxValue = (double) getLongFromDateTime(DateUtils.parseStringWithDefaultHSM(
                            statisticData.max, DateUtils.DATE_FORMATTER_UNIX));
                }
            } else if (column.getPrimitiveType().equals(PrimitiveType.DATETIME)) {
                if (statisticData.isSetMin() && !statisticData.getMin().isEmpty()) {
                    minValue = (double) getLongFromDateTime(DateUtils.parseStringWithDefaultHSM(
                            statisticData.min, DateUtils.DATE_TIME_FORMATTER_UNIX));
                }
                if (statisticData.isSetMax() && !statisticData.getMax().isEmpty()) {
                    maxValue = (double) getLongFromDateTime(DateUtils.parseStringWithDefaultHSM(
                            statisticData.max, DateUtils.DATE_TIME_FORMATTER_UNIX));
                }
            } else {
                if (statisticData.isSetMin() && !statisticData.getMin().isEmpty()) {
                    minValue = Double.parseDouble(statisticData.min);
                }
                if (statisticData.isSetMax() && !statisticData.getMax().isEmpty()) {
                    maxValue = Double.parseDouble(statisticData.max);
                }
            }
        } catch (Exception e) {
            LOG.warn("convert TStatisticData to ColumnStatistics failed, db : {}, table : {}, column : {}, errMsg : {}",
                    db.getFullName(), table.getName(), column.getName(), e.getMessage());
        }

        return builder.setMinValue(minValue).
                setMaxValue(maxValue).
                setDistinctValuesCount(statisticData.countDistinct).
                setAverageRowSize(statisticData.dataSize / Math.max(statisticData.rowCount, 1)).
                setNullsFraction(statisticData.nullCount * 1.0 / Math.max(statisticData.rowCount, 1)).build();
    }
}
