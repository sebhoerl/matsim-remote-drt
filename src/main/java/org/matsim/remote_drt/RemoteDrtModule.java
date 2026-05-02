package org.matsim.remote_drt;

import java.util.HashMap;
import java.util.Map;

import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.core.controler.AbstractModule;

import com.google.common.base.Verify;

public class RemoteDrtModule extends AbstractModule {
    static public final String REMOTE_PORT_PATH_PREFIX = "remote_port_";

    @Override
    public void install() {
        MultiModeDrtConfigGroup multiConfig = MultiModeDrtConfigGroup.get(getConfig());
        Map<String, DrtConfigGroup> drtConfigs = new HashMap<>();
        if (multiConfig != null) {
            for (DrtConfigGroup modeConfig : multiConfig.getModalElements()) {
                drtConfigs.put(modeConfig.getMode(), modeConfig);
            }
        }

        RemoteDrtConfigGroup remoteConfig = RemoteDrtConfigGroup.get(getConfig());
        if (remoteConfig != null) {
            for (RemoteDrtModeParameters parameters : remoteConfig.getModalElements()) {
                install(new RemoteDrtModeModule(parameters));

                DrtConfigGroup drtConfig = drtConfigs.get(parameters.getMode());
                Verify.verifyNotNull(drtConfig, "Cannot find DRT mode " + parameters.getMode()
                        + " although it is defined in remote dispatching.");

                installOverridingQSimModule(new RemoteDrtQSimModeModule(drtConfig));
            }
        }
    }
}
