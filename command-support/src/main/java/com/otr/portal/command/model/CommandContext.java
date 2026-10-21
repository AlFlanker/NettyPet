package com.otr.portal.command.model;

import com.otr.portal.command.config.CommandProperties;
import java.util.Objects;
import lombok.Builder;
import lombok.Value;

/** Stable name and immutable policy shared by all invocations of a command. */
@Value
public class CommandContext {
    String commandName;
    CommandProperties properties;

    @Builder
    public CommandContext(String commandName, CommandProperties properties) {
        this.commandName = Objects.requireNonNull(commandName, "commandName");
        if (commandName.isBlank()) throw new IllegalArgumentException("commandName must not be blank");
        this.properties = Objects.requireNonNull(properties, "properties");
    }
}
