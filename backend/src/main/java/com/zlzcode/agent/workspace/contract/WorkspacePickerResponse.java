package com.zlzcode.agent.workspace.contract;

public record WorkspacePickerResponse(boolean cancelled, WorkspaceRef workspace) {

    public static WorkspacePickerResponse cancelledResponse() {
        return new WorkspacePickerResponse(true, null);
    }

    public static WorkspacePickerResponse selectedResponse(WorkspaceRef workspace) {
        return new WorkspacePickerResponse(false, workspace);
    }
}
