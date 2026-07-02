package dev.pushkin.jvmresearch.enterprise.domain;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FnsProcess {

    private FnsProcessStatus status;
    private String registeringFns;
    private String registeringFnsName;
    private String failMessage;
    private int attempts;
}
