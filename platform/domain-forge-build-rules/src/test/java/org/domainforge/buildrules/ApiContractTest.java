package org.domainforge.buildrules;

import org.domainforge.buildrules.testsupport.ApiContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.AssertionFailedError;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接口契约快照的正反例（T-11）：与快照一致、只差运行地址或顺序时通过；接口形状变化而未更新快照、持续集成中缺快照时失败。
 */
class ApiContractTest {
    private static final String DOCUMENT = """
            {"openapi":"3.1.0","servers":[{"url":"http://127.0.0.1:50001"}],
             "tags":[{"name":"b"},{"name":"a"}],
             "paths":{"/orders":{"get":{"operationId":"list"}}}}""";

    @TempDir
    Path module;

    @Test
    void sameShapePassesRegardlessOfServerAndOrder() throws IOException {
        Path snapshot = module.resolve(ApiContract.SNAPSHOT);
        ApiContract.verify(snapshot, DOCUMENT, true, true);
        String reordered = """
                {"paths":{"/orders":{"get":{"operationId":"list"}}},"tags":[{"name":"a"},{"name":"b"}],
                 "servers":[{"url":"http://127.0.0.1:50002"}],"openapi":"3.1.0"}""";

        ApiContract.verify(snapshot, reordered, false, true);

        assertFalse(Files.readString(snapshot).contains("servers"));
    }

    @Test
    void changedShapeWithoutUpdatedSnapshotFails() {
        Path snapshot = module.resolve(ApiContract.SNAPSHOT);
        ApiContract.verify(snapshot, DOCUMENT, true, false);
        String changed = DOCUMENT.replace("\"operationId\":\"list\"", "\"operationId\":\"listAll\"");

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> ApiContract.verify(snapshot, changed, false, false));

        assertTrue(failure.getMessage().contains("listAll"), failure.getMessage());
        ApiContract.verify(snapshot, changed, true, false);
        ApiContract.verify(snapshot, changed, false, false);
    }

    @Test
    void missingSnapshotIsWrittenLocallyButFailsInCi() throws IOException {
        Path snapshot = module.resolve(ApiContract.SNAPSHOT);

        assertThrows(AssertionFailedError.class, () -> ApiContract.verify(snapshot, DOCUMENT, false, true));
        assertFalse(Files.exists(snapshot));

        ApiContract.verify(snapshot, DOCUMENT, false, false);
        assertEquals(ApiContract.normalize(DOCUMENT), Files.readString(snapshot));
    }
}
