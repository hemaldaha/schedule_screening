package com.kpmg.aml.screening.runner;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
public class ManualRun {

    private final ScreeningTrigger screeningTrigger;
    public ManualRun(ScreeningTrigger screeningTrigger){
        this.screeningTrigger =screeningTrigger;
    }

    @GetMapping("aml/screening/schedule")
    public void manualRun (){
        this.screeningTrigger.manualRun();
    }
}
