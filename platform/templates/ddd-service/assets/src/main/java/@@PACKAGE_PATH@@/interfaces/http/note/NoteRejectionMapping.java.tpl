package @@PACKAGE@@.interfaces.http.note;

import @@PACKAGE@@.application.command.note.NoteRejected;
import org.domainforge.shared.ApiResponse;
import org.domainforge.web.api.BusinessRejectionMapping;
import org.springframework.stereotype.Component;

/**
 * 把便笺业务拒绝映射为稳定代码，HTTP 200。
 */
@Component
public class NoteRejectionMapping implements BusinessRejectionMapping<NoteRejected> {
    @Override
    public Class<NoteRejected> exceptionType() {
        return NoteRejected.class;
    }

    @Override
    public ApiResponse<Void> map(NoteRejected exception) {
        return ApiResponse.rejected(exception.code(), exception.getMessage());
    }
}
