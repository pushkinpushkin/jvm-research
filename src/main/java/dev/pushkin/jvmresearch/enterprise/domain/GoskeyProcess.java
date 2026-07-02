package dev.pushkin.jvmresearch.enterprise.domain;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GoskeyProcess {

    private GoskeyProcessStatus status;
    private String failMessage;
}
