package com.genius.smartlight.websocket;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WsMessage<T> {

    private String type;
    private T data;

    public static <T> WsMessage<T> of(String type, T data) {
        return new WsMessage<>(type, data);
    }
}