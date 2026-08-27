package com.zlzcode.codeagent.workspace.model;

import java.nio.file.Path;

public record AuthorizedWorkspace(String id, String name, Path root) {
}
