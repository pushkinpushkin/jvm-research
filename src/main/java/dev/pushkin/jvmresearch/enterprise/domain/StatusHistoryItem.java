package dev.pushkin.jvmresearch.enterprise.domain;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class StatusHistoryItem {

    private String status;
    private String source;
    private String message;
    private Instant occurredAt;
}
