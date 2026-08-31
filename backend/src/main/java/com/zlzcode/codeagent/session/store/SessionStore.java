package com.zlzcode.codeagent.session.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlzcode.codeagent.session.exception.SessionException;
import com.zlzcode.codeagent.session.model.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Component
public final class SessionStore {

    private static final int STATE_VERSION = 1;
    private static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_TURNS = 1_000;
    private static final int MAX_MESSAGE_CHARS = 100_000;
    private static final int MAX_TOOL_HISTORY = 15;
    private static final int MAX_TOOL_NAME_CHARS = 64;
    private static final int MAX_TOOL_VALUE_CHARS = 20_000;
    private static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");
    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+$");

    private final ObjectMapper objectMapper;
    private final Path directory;

    @Autowired
    public SessionStore(
            ObjectMapper objectMapper,
            @Value("${codeagent.session.directory:}") String configuredDirectory) {
        this(objectMapper, configuredDirectory == null || configuredDirectory.isBlank()
                ? defaultDirectory()
                : Path.of(configuredDirectory.trim()));
    }

    public SessionStore(ObjectMapper objectMapper, Path directory) {
        this.objectMapper = objectMapper;
        this.directory = directory.toAbsolutePath().normalize();
    }

    public Session read(String sessionId) {
        Path target = sessionFile(sessionId);
        try {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(target)) {
                if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw SessionException.notFound();
                }
                throw SessionException.stateCorrupted();
            }
            long size = Files.size(target);
            if (size <= 0 || size > MAX_FILE_BYTES) {
                throw SessionException.stateCorrupted();
            }
            byte[] encoded = Files.readAllBytes(target);
            if (encoded.length == 0 || encoded.length > MAX_FILE_BYTES) {
                throw SessionException.stateCorrupted();
            }
            SessionFile file = objectMapper.readValue(encoded, SessionFile.class);
            return validateAndMap(file, sessionId);
        } catch (SessionException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw SessionException.stateCorrupted();
        }
    }

    public void create(Session session) {
        write(session, false);
    }

    public void save(Session session) {
        write(session, true);
    }

    /*
     * 背景：Session 在模型调用前后都要落盘，进程中断不能破坏此前已经完成的会话历史。
     * 设计意图：把完整快照写入同目录临时文件并强制刷新，再用原子移动替换正式文件；不直接覆盖目标。
     * 关键约束：临时文件必须与目标同目录，且不允许在原子移动不受支持时降级，否则可能留下半个 JSON 文件。
     */
    private void write(Session session, boolean replaceExisting) {
        validateSession(session, SessionException::persistenceFailed);
        Path target = sessionFile(session.sessionId());
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(target)) {
                throw SessionException.stateCorrupted();
            }
            if (replaceExisting && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw SessionException.notFound();
            }
            if (!replaceExisting && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw SessionException.persistenceFailed();
            }

            byte[] encoded = objectMapper.writeValueAsBytes(SessionFile.from(session));
            if (encoded.length == 0 || encoded.length > MAX_FILE_BYTES) {
                throw SessionException.persistenceFailed();
            }
            temporary = Files.createTempFile(directory, ".session-", ".tmp");
            try (FileChannel channel = FileChannel.open(
                    temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(encoded);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }

            if (replaceExisting) {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            }
            temporary = null;
        } catch (SessionException exception) {
            throw exception;
        } catch (AtomicMoveNotSupportedException exception) {
            throw SessionException.persistenceFailed();
        } catch (IOException | RuntimeException exception) {
            throw SessionException.persistenceFailed();
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private Session validateAndMap(SessionFile file, String expectedSessionId) {
        if (file == null
                || file.version() != STATE_VERSION
                || !expectedSessionId.equals(file.sessionId())) {
            throw SessionException.stateCorrupted();
        }
        Session session = new Session(
                file.sessionId(), file.workspaceId(), file.createdAt(), file.updatedAt(),
                file.turns() == null ? List.of() : file.turns().stream().map(TurnFile::toModel).toList());
        validateSession(session, SessionException::stateCorrupted);
        return session;
    }

    private void validateSession(
            Session session,
            Supplier<SessionException> invalidState) {
        if (session == null
                || !validId(session.sessionId())
                || !validId(session.workspaceId())
                || session.createdAt() == null
                || session.updatedAt() == null
                || session.updatedAt().isBefore(session.createdAt())
                || session.turns().size() > MAX_TURNS) {
            throw invalidState.get();
        }
        Set<String> runIds = new HashSet<>();
        for (Session.Turn turn : session.turns()) {
            validateTurn(turn, runIds, invalidState);
        }
    }

    private void validateTurn(
            Session.Turn turn,
            Set<String> runIds,
            Supplier<SessionException> invalidState) {
        if (turn == null
                || !validId(turn.runId())
                || !runIds.add(turn.runId())
                || turn.state() == null
                || turn.createdAt() == null
                || turn.user() == null
                || invalidContent(turn.user().content())) {
            throw invalidState.get();
        }
        if (turn.state() == Session.TurnState.INCOMPLETE) {
            if (turn.assistant() != null) {
                throw invalidState.get();
            }
            return;
        }
        if (turn.assistant() == null || invalidContent(turn.assistant().content())
                || turn.assistant().toolHistory().size() > MAX_TOOL_HISTORY) {
            throw invalidState.get();
        }
        for (Session.ToolHistory tool : turn.assistant().toolHistory()) {
            if (tool == null
                    || tool.name() == null
                    || tool.name().isBlank()
                    || tool.name().length() > MAX_TOOL_NAME_CHARS
                    || !TOOL_NAME_PATTERN.matcher(tool.name()).matches()
                    || tool.arguments() == null
                    || tool.arguments().length() > MAX_TOOL_VALUE_CHARS
                    || tool.result() == null
                    || tool.result().length() > MAX_TOOL_VALUE_CHARS) {
                throw invalidState.get();
            }
        }
    }

    private boolean invalidContent(String content) {
        return content == null || content.isBlank() || content.length() > MAX_MESSAGE_CHARS;
    }

    private boolean validId(String value) {
        return value != null && ID_PATTERN.matcher(value).matches();
    }

    private Path sessionFile(String sessionId) {
        if (!validId(sessionId)) {
            throw SessionException.notFound();
        }
        return directory.resolve(sessionId + ".json");
    }

    private static Path defaultDirectory() {
        Path workingDirectory = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        if (workingDirectory.getFileName() != null
                && "backend".equalsIgnoreCase(workingDirectory.getFileName().toString())) {
            return workingDirectory.getParent().resolve("sessions");
        }
        return workingDirectory.resolve("sessions");
    }

    private record SessionFile(
            int version,
            String sessionId,
            String workspaceId,
            Instant createdAt,
            Instant updatedAt,
            List<TurnFile> turns) {

        private static SessionFile from(Session session) {
            return new SessionFile(
                    STATE_VERSION,
                    session.sessionId(),
                    session.workspaceId(),
                    session.createdAt(),
                    session.updatedAt(),
                    session.turns().stream().map(TurnFile::from).toList());
        }
    }

    private record TurnFile(
            String runId,
            String state,
            Instant createdAt,
            Session.UserMessage user,
            Session.AssistantMessage assistant) {

        private static TurnFile from(Session.Turn turn) {
            return new TurnFile(
                    turn.runId(),
                    turn.state().name().toLowerCase(Locale.ROOT),
                    turn.createdAt(),
                    turn.user(),
                    turn.assistant());
        }

        private Session.Turn toModel() {
            Session.TurnState parsedState;
            try {
                parsedState = Session.TurnState.valueOf(state.toUpperCase(Locale.ROOT));
            } catch (RuntimeException exception) {
                throw SessionException.stateCorrupted();
            }
            return new Session.Turn(runId, parsedState, createdAt, user, assistant);
        }
    }
}
