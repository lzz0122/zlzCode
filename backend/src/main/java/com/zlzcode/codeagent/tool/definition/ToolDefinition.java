package com.zlzcode.codeagent.tool.definition;

import java.util.Map;

public interface ToolDefinition {

    String name();

    String displayName();

    String description();

    Map<String, Object> parametersSchema();

    Validation validate(String arguments);

    record Validation(boolean valid, String code, String presentation) {

        public static Validation accepted() {
            return new Validation(true, null, null);
        }

        public static Validation rejected(String code, String presentation) {
            return new Validation(false, code, presentation);
        }
    }
}
