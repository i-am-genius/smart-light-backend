package com.genius.smartlight;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class SmartLightStartupLogger {

    private static final Logger log = LoggerFactory.getLogger(SmartLightStartupLogger.class);

    private static final String BANNER = """

============================================================
   ____            _              _     _       _     _
  / ___| ___ _ __ (_)_   _ ___   | |   (_) __ _| |__ | |_
 | |  _ / _ \\ '_ \\| | | | / __|  | |   | |/ _` | '_ \\| __|
 | |_| |  __/ | | | | |_| \\__ \\  | |___| | (_| | | | | |_
  \\____|\\___|_| |_|_|\\__,_|___/  |_____|_|\\__, |_| |_|\\__|
                                          |___/
============================================================
""";

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.info("{}", BANNER);
    }
}
