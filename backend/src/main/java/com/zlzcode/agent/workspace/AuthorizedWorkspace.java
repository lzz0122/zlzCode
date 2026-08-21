package com.zlzcode.agent.workspace;

import java.nio.file.Path;

public record AuthorizedWorkspace(String id, String name, Path root) {
}
