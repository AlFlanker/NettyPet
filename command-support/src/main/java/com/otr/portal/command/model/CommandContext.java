package com.otr.portal.command.model;

import com.otr.portal.command.config.CommandProperties;
import java.util.Objects;
import lombok.Builder;
import lombok.Value;

/** Неизменяемые имя и настройки, по которым все вызовы команды находят общие политики. */
@Value
public class CommandContext {
    String commandName;
    CommandProperties properties;

    /** Проверяет имя и настройки до обращения к реестру, чтобы не создавать непригодную запись. */
    @Builder
    public CommandContext(String commandName, CommandProperties properties) {
        this.commandName = Objects.requireNonNull(commandName, "commandName");
        if (commandName.isBlank()) throw new IllegalArgumentException("commandName must not be blank");
        this.properties = Objects.requireNonNull(properties, "properties");
    }
}
