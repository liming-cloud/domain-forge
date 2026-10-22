package org.domainforge.iam.application.query.operation;

import java.util.UUID;
import org.domainforge.iam.application.port.operation.CommandResult;

/** confirmed只表示存在未过期收据；false不能据此推断操作从未发生。 */
public record OperationObservation(UUID operationId, boolean confirmed, CommandResult result) {}
