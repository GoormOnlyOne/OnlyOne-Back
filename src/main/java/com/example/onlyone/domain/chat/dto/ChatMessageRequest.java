package com.example.onlyone.domain.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageRequest {

    @JsonProperty("user_id")
    private Long user_id;

    @JsonProperty("text")
    private String text;
}
