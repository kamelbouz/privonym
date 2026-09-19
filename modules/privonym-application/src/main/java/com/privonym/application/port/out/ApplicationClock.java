package com.privonym.application.port.out;

import java.time.Instant;

public interface ApplicationClock {
    Instant now();
}
