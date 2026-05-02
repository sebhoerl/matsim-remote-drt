package org.matsim.remote_drt;

import java.util.Collection;

import org.matsim.contrib.dvrp.run.MultiModal;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ReflectiveConfigGroup;

public class RemoteDrtConfigGroup extends ReflectiveConfigGroup implements MultiModal<RemoteDrtModeParameters> {
    public static final String GROUP_NAME = "remote_drt";

    public RemoteDrtConfigGroup() {
        super(GROUP_NAME);
    }

    @Override
    public ConfigGroup createParameterSet(String type) {
        if (type.equals(RemoteDrtModeParameters.GROUP_NAME)) {
            return new RemoteDrtModeParameters();
        } else {
            throw new IllegalArgumentException("Unsupported parameter set type: " + type);
        }
    }

    @Override
    public void addParameterSet(ConfigGroup set) {
        if (set instanceof RemoteDrtModeParameters) {
            super.addParameterSet(set);
        } else {
            throw new IllegalArgumentException("Unsupported parameter set class: " + set);
        }
    }

    public void addModeParameters(RemoteDrtModeParameters parameters) {
        addParameterSet(parameters);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Collection<RemoteDrtModeParameters> getModalElements() {
        return (Collection<RemoteDrtModeParameters>) getParameterSets(RemoteDrtModeParameters.GROUP_NAME);
    }

    public static RemoteDrtConfigGroup get(Config config) {
        return (RemoteDrtConfigGroup) config.getModules().get(GROUP_NAME);
    }
}
