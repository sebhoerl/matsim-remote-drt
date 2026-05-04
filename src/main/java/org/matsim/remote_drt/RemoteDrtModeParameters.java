package org.matsim.remote_drt;

import org.matsim.contrib.dvrp.run.Modal;
import org.matsim.core.config.ReflectiveConfigGroup;
import org.matsim.core.config.ReflectiveConfigGroup.Parameter;

public class RemoteDrtModeParameters extends ReflectiveConfigGroup implements Modal {
    public static final String GROUP_NAME = "mode";

    @Parameter
    @Comment("The mode for which the remote dispatcher is configured")
    private String mode;

    @Parameter
    @Comment("The port at which the dispatcher should listen. Put zero for random selection.")
    private int port;

    @Parameter
    @Comment("If not message has been received after this period (in seconds), an exception is thrown. Set zero for no timeout.")
    private double timeout = 60.0;

    @Parameter
    @Comment("Automatically rejects requests that have passed the latest pickup time simulator-side")
    private boolean useAutomaticRejection = false;

    public RemoteDrtModeParameters() {
        super(GROUP_NAME);
    }

    @Override
    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public double getTimeout() {
        return timeout;
    }

    public void setTimeout(double timeout) {
        this.timeout = timeout;
    }

    public boolean getUseAutomaticRejection() {
        return useAutomaticRejection;
    }

    public void setUseAutomaticRejection(boolean useAutomaticRejection) {
        this.useAutomaticRejection = useAutomaticRejection;
    }
}
