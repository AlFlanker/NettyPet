package com.otr.portal.command.model;

import com.otr.portal.command.config.CommandProperties;
import lombok.Builder;
import lombok.Value;

/**
 * Контекст команды
 *
 * @author alexraspopin
 * @since 09.02.2026
 */
@Value
@Builder
public class CommandContext {
    String commandName;
    CommandProperties properties;
}
