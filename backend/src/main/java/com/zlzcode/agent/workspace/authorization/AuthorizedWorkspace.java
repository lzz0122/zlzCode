package com.zlzcode.agent.workspace.authorization;

import java.nio.file.Path;

public record AuthorizedWorkspace(String id, String name, Path root) {
}
