package com.zlzcode.codeagent.tool.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Service
public final class TextMutationSupport {

    public String decode(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    /*
     * 背景：write 与 edit 都要把审批绑定的小文本内容落到同一目标，重复实现临时文件协议会产生行为分叉。
     * 设计意图：统一使用目标同目录临时文件和原子移动，而不是直接覆盖目标或在不同工具中复制提交算法。
     * 关键约束：不能降级为普通复制覆盖；否则失败时可能留下部分内容，且审批后的提交结果无法可靠收口。
     */
    public void atomicWrite(Path target, byte[] bytes, boolean replace) throws IOException {
        Path parent = target.getParent();
        String prefix = "." + target.getFileName() + ".";
        Path temporary = Files.createTempFile(parent, prefix, ".tmp");
        try {
            Files.write(temporary, bytes);
            if (replace) {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
