package dev.pushkin.jvmresearch.enterprise.domain;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AucProcess {

    private String status;
    private String ecpSerialNumber;
    private String failMessage;
}
