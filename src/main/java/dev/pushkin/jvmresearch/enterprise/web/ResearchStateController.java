package dev.pushkin.jvmresearch.enterprise.web;

import dev.pushkin.jvmresearch.enterprise.kafka.InMemoryBusinessEventDeduplicationService;
import dev.pushkin.jvmresearch.enterprise.service.ResearchMetrics;

import lombok.RequiredArgsConstructor;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class ResearchStateController {
    private final MongoTemplate mongo;
    private final ResearchMetrics metrics;
    private final InMemoryBusinessEventDeduplicationService cache;

    @GetMapping("/research/state")
    public Map<String, Object> state() {
        String group =
                "{_id:null, orders:{$sum:1}, historyMax:{$max:{$size:'$history'}},"
                    + " eventIdsMax:{$max:{$size:'$processedBusinessEvents'}},"
                    + " pendingEvents:{$sum:{$size:'$pendingEvents'}},"
                    + " waiting:{$sum:{$cond:[{$eq:['$status','WAITING_EXTERNAL_STATUS']},1,0]}},"
                    + " retryable:{$sum:{$cond:[{$and:[{$eq:['$status','FAILED']},{$lt:['$fnsProcess.attempts',3]}]},1,0]}}}";
        Document bounds =
                mongo.getCollection("orders")
                        .aggregate(List.of(new Document("$group", Document.parse(group))))
                        .first();
        if (bounds == null)
            bounds =
                    new Document("orders", 0)
                            .append("historyMax", 0)
                            .append("eventIdsMax", 0)
                            .append("pendingEvents", 0)
                            .append("waiting", 0)
                            .append("retryable", 0);
        bounds.remove("_id");
        return Map.of(
                "counters", metrics.snapshot(), "bounds", bounds, "dedupCacheSize", cache.size());
    }
}
