// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Inc.

#include "exec/pipeline/result_sink_operator.h"

#include "column/chunk.h"
#include "exprs/expr.h"
#include "runtime/buffer_control_block.h"
#include "runtime/mysql_result_writer.h"
#include "runtime/query_statistics.h"
#include "runtime/result_buffer_mgr.h"
#include "runtime/runtime_state.h"
#include "runtime/statistic_result_writer.h"

namespace starrocks::pipeline {
Status ResultSinkOperator::prepare(RuntimeState* state) {
    Operator::prepare(state);

    // Create profile
    _profile = std::make_unique<RuntimeProfile>("result sink");

    // Create writer based on sink type
    switch (_sink_type) {
    case TResultSinkType::MYSQL_PROTOCAL:
        _writer = std::make_shared<MysqlResultWriter>(_sender.get(), _output_expr_ctxs, _profile.get());
        break;
    case TResultSinkType::STATISTIC:
        _writer = std::make_shared<vectorized::StatisticResultWriter>(_sender.get(), _output_expr_ctxs, _profile.get());
        break;
    default:
        return Status::InternalError("Unknown result sink type");
    }

    RETURN_IF_ERROR(_writer->init(state));
    return Status::OK();
}

void ResultSinkOperator::close(RuntimeState* state) {
    Status st;
    // Close the writer
    if (_writer != nullptr) {
        st = _writer->close();
        _num_written_rows.fetch_add(_writer->get_written_rows(), std::memory_order_relaxed);
    }

    // Close the shared sender when the last result sink operator is closing.
    if (_num_result_sinkers.fetch_sub(1, std::memory_order_acq_rel) == 1) {
        if (_sender != nullptr) {
            // Incrementing and reading _num_written_rows needn't memory barrier, because
            // the visibility of _num_written_rows is guaranteed by _num_result_sinkers.fetch_sub().
            _sender->update_num_written_rows(_num_written_rows.load(std::memory_order_relaxed));

            auto query_statistic = std::make_shared<QueryStatistics>();
            QueryContext* query_ctx = state->query_ctx();
            query_statistic->add_scan_stats(query_ctx->cur_scan_rows_num(), query_ctx->get_scan_bytes());
            query_statistic->add_cpu_costs(query_ctx->cpu_cost());
            query_statistic->add_mem_costs(query_ctx->mem_cost_bytes());
            query_statistic->set_returned_rows(_num_written_rows);
            _sender->set_query_statistics(query_statistic);

            Status final_status = _fragment_ctx->final_status();
            if (!st.ok() && final_status.ok()) {
                final_status = st;
            }
            _sender->close(final_status);
        }

        state->exec_env()->result_mgr()->cancel_at_time(time(nullptr) + config::result_buffer_cancelled_interval_time,
                                                        state->fragment_instance_id());
    }

    Operator::close(state);
}

StatusOr<vectorized::ChunkPtr> ResultSinkOperator::pull_chunk(RuntimeState* state) {
    return Status::InternalError("Shouldn't pull chunk from result sink operator");
}

Status ResultSinkOperator::set_cancelled(RuntimeState* state) {
    SCOPED_THREAD_LOCAL_MEM_TRACKER_SETTER(nullptr);

    _fetch_data_result.clear();
    return Status::OK();
}

bool ResultSinkOperator::need_input() const {
    SCOPED_THREAD_LOCAL_MEM_TRACKER_SETTER(nullptr);

    if (is_finished()) {
        return false;
    }
    if (_fetch_data_result.empty()) {
        return true;
    }

    auto status = _writer->try_add_batch(_fetch_data_result);
    if (status.ok()) {
        return status.value();
    } else {
        _last_error = status.status();
        return true;
    }
}

Status ResultSinkOperator::push_chunk(RuntimeState* state, const vectorized::ChunkPtr& chunk) {
    // The ResultWriter memory that sends the results is no longer recorded to the query memory.
    // There are two reason:
    // 1. the query result has come out, and then the memory limit is triggered, cancel, it is not necessary
    // 2. if this memory is counted, The memory of the receiving thread needs to be recorded,
    // and the life cycle of MemTracker needs to be considered
    //
    // All the places where acquire and release memory of _fetch_data_result must use process_mem_tracker.
    SCOPED_THREAD_LOCAL_MEM_TRACKER_SETTER(nullptr);

    if (!_last_error.ok()) {
        return _last_error;
    }
    DCHECK(_fetch_data_result.empty());

    auto status = _writer->process_chunk(chunk.get());
    if (status.ok()) {
        _fetch_data_result = std::move(status.value());
        return _writer->try_add_batch(_fetch_data_result).status();
    } else {
        return status.status();
    }
}

Status ResultSinkOperatorFactory::prepare(RuntimeState* state) {
    RETURN_IF_ERROR(OperatorFactory::prepare(state));
    RETURN_IF_ERROR(state->exec_env()->result_mgr()->create_sender(state->fragment_instance_id(), 1024, &_sender));

    RETURN_IF_ERROR(Expr::create_expr_trees(state->obj_pool(), _t_output_expr, &_output_expr_ctxs));

    RETURN_IF_ERROR(Expr::prepare(_output_expr_ctxs, state));
    RETURN_IF_ERROR(Expr::open(_output_expr_ctxs, state));

    return Status::OK();
}

void ResultSinkOperatorFactory::close(RuntimeState* state) {
    Expr::close(_output_expr_ctxs, state);
    OperatorFactory::close(state);
}

} // namespace starrocks::pipeline
