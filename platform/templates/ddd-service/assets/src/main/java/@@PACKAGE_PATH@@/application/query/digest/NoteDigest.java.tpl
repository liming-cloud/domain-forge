package @@PACKAGE@@.application.query.digest;

import java.util.List;

/**
 * 便笺摘要：可见便笺总数与前若干条标题。
 *
 * @param count  可见便笺数
 * @param titles 前 10 条标题
 */
public record NoteDigest(int count, List<String> titles) {
}
