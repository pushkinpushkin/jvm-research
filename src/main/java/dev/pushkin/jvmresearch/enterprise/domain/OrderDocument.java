package dev.pushkin.jvmresearch.enterprise.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("orders")
@CompoundIndex(name = "status_updated_idx", def = "{'status': 1, 'updatedAt': 1}")
@CompoundIndex(name = "fns_status_updated_idx", def = "{'fnsProcess.status': 1, 'updatedAt': 1}")
public class OrderDocument {

    @Id
    private String id;

    private OrderStatus status;
    private String clientType;
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

    public static OrderDocument newOrder(String id, String clientType, String clientId, Map<String, Object> payload) {
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
        order.addHistory(OrderStatus.NEW.name(), "generator", "Synthetic order generated", now);
        return order;
    }

    public void addHistory(String status, String source, String message, Instant occurredAt) {
        history.add(new StatusHistoryItem(status, source, message, occurredAt));
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public String getClientType() {
        return clientType;
    }

    public void setClientType(String clientType) {
        this.clientType = clientType;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getExternalRequestId() {
        return externalRequestId;
    }

    public void setExternalRequestId(String externalRequestId) {
        this.externalRequestId = externalRequestId;
    }

    public FnsProcess getFnsProcess() {
        return fnsProcess;
    }

    public void setFnsProcess(FnsProcess fnsProcess) {
        this.fnsProcess = fnsProcess;
    }

    public AucProcess getAucProcess() {
        return aucProcess;
    }

    public void setAucProcess(AucProcess aucProcess) {
        this.aucProcess = aucProcess;
    }

    public GoskeyProcess getGoskeyProcess() {
        return goskeyProcess;
    }

    public void setGoskeyProcess(GoskeyProcess goskeyProcess) {
        this.goskeyProcess = goskeyProcess;
    }

    public List<StatusHistoryItem> getHistory() {
        return history;
    }

    public void setHistory(List<StatusHistoryItem> history) {
        this.history = history;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload;
    }

    public List<String> getProcessedBusinessEvents() {
        return processedBusinessEvents;
    }

    public void setProcessedBusinessEvents(List<String> processedBusinessEvents) {
        this.processedBusinessEvents = processedBusinessEvents;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
