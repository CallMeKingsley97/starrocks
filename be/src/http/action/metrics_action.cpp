// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#include "http/action/metrics_action.h"

#include <rapidjson/document.h>
#include <rapidjson/rapidjson.h>
#include <rapidjson/stringbuffer.h>
#include <rapidjson/writer.h>

#include <string>

#include "common/tracer.h"
#include "http/http_channel.h"
#include "http/http_headers.h"
#include "http/http_request.h"
#include "util/metrics.h"

#ifdef USE_STAROS
#include "metrics/metrics.h"
#endif

namespace starrocks {

class PrometheusMetricsVisitor : public MetricsVisitor {
public:
    ~PrometheusMetricsVisitor() override = default;
    void visit(const std::string& prefix, const std::string& name, MetricCollector* collector) override;
    std::string to_string() const { return _ss.str(); }

    std::ostream& output_stream() { return _ss; }

private:
    void _visit_simple_metric(const std::string& name, const MetricLabels& labels, Metric* metric);

private:
    std::stringstream _ss;
};

// eg:
// starrocks_be_process_fd_num_used LONG 43
// starrocks_be_process_thread_num LONG 240
class SimpleCoreMetricsVisitor : public MetricsVisitor {
public:
    ~SimpleCoreMetricsVisitor() override = default;
    void visit(const std::string& prefix, const std::string& name, MetricCollector* collector) override;
    std::string to_string() const { return _ss.str(); }

private:
    std::stringstream _ss;
    static const std::string PROCESS_FD_NUM_USED;
    static const std::string PROCESS_THREAD_NUM;
    static const std::string PUSH_REQUEST_WRITE_BYTES_PER_SECOND;
    static const std::string QUERY_SCAN_BYTES_PER_SECOND;
    static const std::string MAX_DISK_IO_UTIL_PERCENT;
    static const std::string MAX_NETWORK_SEND_BYTES_RATE;
    static const std::string MAX_NETWORK_RECEIVE_BYTES_RATE;
};

const std::string SimpleCoreMetricsVisitor::PROCESS_FD_NUM_USED = "process_fd_num_used";
const std::string SimpleCoreMetricsVisitor::PROCESS_THREAD_NUM = "process_thread_num";
const std::string SimpleCoreMetricsVisitor::PUSH_REQUEST_WRITE_BYTES_PER_SECOND = "push_request_write_bytes_per_second";
const std::string SimpleCoreMetricsVisitor::QUERY_SCAN_BYTES_PER_SECOND = "query_scan_bytes_per_second";
const std::string SimpleCoreMetricsVisitor::MAX_DISK_IO_UTIL_PERCENT = "max_disk_io_util_percent";
const std::string SimpleCoreMetricsVisitor::MAX_NETWORK_SEND_BYTES_RATE = "max_network_send_bytes_rate";
const std::string SimpleCoreMetricsVisitor::MAX_NETWORK_RECEIVE_BYTES_RATE = "max_network_receive_bytes_rate";

void PrometheusMetricsVisitor::visit(const std::string& prefix, const std::string& name, MetricCollector* collector) {
    if (collector->empty() || name.empty()) {
        return;
    }
    std::string metric_name;
    if (prefix.empty()) {
        metric_name = name;
    } else {
        metric_name = prefix + "_" + name;
    }
    // Output metric type
    _ss << "# TYPE " << metric_name << " " << collector->type() << "\n";
    switch (collector->type()) {
    case MetricType::COUNTER:
    case MetricType::GAUGE:
        for (auto& it : collector->metrics()) {
            _visit_simple_metric(metric_name, it.first, (Metric*)it.second);
        }
        break;
    default:
        break;
    }
}

void PrometheusMetricsVisitor::_visit_simple_metric(const std::string& name, const MetricLabels& labels,
                                                    Metric* metric) {
    _ss << name;
    // labels
    if (!labels.empty()) {
        _ss << "{";
        int i = 0;
        for (auto& label : labels.labels) {
            if (i++ > 0) {
                _ss << ",";
            }
            _ss << label.name << "=\"" << label.value << "\"";
        }
        _ss << "}";
    }
    _ss << " " << metric->to_string() << "\n";
}

void SimpleCoreMetricsVisitor::visit(const std::string& prefix, const std::string& name, MetricCollector* collector) {
    if (collector->empty() || name.empty()) {
        return;
    }

    if (name != PROCESS_FD_NUM_USED && name != PROCESS_THREAD_NUM && name != PUSH_REQUEST_WRITE_BYTES_PER_SECOND &&
        name != QUERY_SCAN_BYTES_PER_SECOND && name != MAX_DISK_IO_UTIL_PERCENT &&
        name != MAX_NETWORK_SEND_BYTES_RATE && name != MAX_NETWORK_RECEIVE_BYTES_RATE) {
        return;
    }

    std::string metric_name;
    if (prefix.empty()) {
        metric_name = name;
    } else {
        metric_name = prefix + "_" + name;
    }

    for (auto& it : collector->metrics()) {
        _ss << metric_name << " LONG " << ((Metric*)it.second)->to_string() << "\n";
    }
}

class JsonMetricsVisitor : public MetricsVisitor {
public:
    JsonMetricsVisitor() = default;
    ~JsonMetricsVisitor() override = default;
    void visit(const std::string& prefix, const std::string& name, MetricCollector* collector) override;
    std::string to_string() {
        rapidjson::StringBuffer strBuf;
        rapidjson::Writer<rapidjson::StringBuffer> writer(strBuf);
        doc.Accept(writer);
        return strBuf.GetString();
    }

private:
    rapidjson::Document doc{rapidjson::kArrayType};
};

void JsonMetricsVisitor::visit(const std::string& prefix, const std::string& name, MetricCollector* collector) {
    if (collector->empty() || name.empty()) {
        return;
    }

    rapidjson::Document::AllocatorType& allocator = doc.GetAllocator();
    switch (collector->type()) {
    case MetricType::COUNTER:
    case MetricType::GAUGE:
        for (auto& it : collector->metrics()) {
            const MetricLabels& labels = it.first;
            Metric* metric = reinterpret_cast<Metric*>(it.second);
            rapidjson::Value metric_obj(rapidjson::kObjectType);
            rapidjson::Value tag_obj(rapidjson::kObjectType);
            tag_obj.AddMember("metric", rapidjson::Value(name.c_str(), allocator), allocator);
            // labels
            if (!labels.empty()) {
                for (auto& label : labels.labels) {
                    tag_obj.AddMember(rapidjson::Value(label.name.c_str(), allocator),
                                      rapidjson::Value(label.value.c_str(), allocator), allocator);
                }
            }
            metric_obj.AddMember("tags", tag_obj, allocator);
            rapidjson::Value unit_val(unit_name(metric->unit()), allocator);
            metric_obj.AddMember("unit", unit_val, allocator);
            metric->write_value(metric_obj, allocator);
            doc.PushBack(metric_obj, allocator);
        }
        break;
    default:
        break;
    }
}

void MetricsAction::handle(HttpRequest* req) {
    auto scoped_span = trace::Scope(Tracer::Instance().start_trace("http_handle_metrics"));
    const std::string& type = req->param("type");
    std::string str;
    if (type == "core") {
        SimpleCoreMetricsVisitor visitor;
        _metrics->collect(&visitor);
        str.assign(visitor.to_string());
    } else if (type == "json") {
        JsonMetricsVisitor visitor;
        _metrics->collect(&visitor);
        str.assign(visitor.to_string());
    } else {
        PrometheusMetricsVisitor visitor;
        _metrics->collect(&visitor);
#ifdef USE_STAROS
        staros::starlet::metrics::MetricsSystem::instance()->text_serializer(visitor.output_stream());
#endif
        str.assign(visitor.to_string());
    }

    req->add_output_header(HttpHeaders::CONTENT_TYPE, "text/plain; version=0.0.4");
    HttpChannel::send_reply(req, str);
}

} // namespace starrocks
