package dev.pushkin.jvmresearch.enterprise.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import dev.pushkin.jvmresearch.enterprise.kafka.BusinessEventSource;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Getter
@Setter
@Document("orders")
@CompoundIndex(name = "status_updated_idx", def = "{'status': 1, 'updatedAt': 1}")
@CompoundIndex(name = "fns_status_updated_idx", def = "{'fnsProcess.status': 1, 'updatedAt': 1}")
public class OrderDocument {

    @Id
    private String id;

    private OrderStatus status;
    private ClientType clientType;
    private String clientId;
    private String externalRequestId;
    private FnsProcess fnsProcess = new FnsProcess();
    private AucProcess aucProcess = new AucProcess();
    private GoskeyProcess goskeyProcess = new GoskeyProcess();
    private List<StatusHistoryItem> history = new ArrayList<>();
    private Map<String, Object> payload = new HashMap<>();
    private List<String> processedBusinessEvents = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private Long version;

    public static OrderDocument newOrder(String id, ClientType clientType, String clientId, Map<String, Object> payload) {
        Instant now = Instant.now();
        OrderDocument order = new OrderDocument();
        order.setId(id);
        order.setStatus(OrderStatus.NEW);
        order.setClientType(clientType);
        order.setClientId(clientId);
        order.setExternalRequestId("process-" + id);
        order.setPayload(payload);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        order.addHistory(OrderStatus.NEW.name(), BusinessEventSource.GENERATOR.value(), "Synthetic order generated", now);
        return order;
    }

    public void addHistory(String status, String source, String message, Instant occurredAt) {
        history.add(new StatusHistoryItem(status, source, message, occurredAt));
    }
}
