package dev.muggel.wake.features.drydock;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.module.AbstractModule;
import dev.muggel.wake.features.drydock.api.DrydockService;
import dev.muggel.wake.features.drydock.integration.obu.OBUBoostpadIntegration;
import dev.muggel.wake.features.drydock.listeners.BoostpadDetectorListener;
import dev.muggel.wake.features.drydock.service.DrydockServiceImpl;

public class DrydockModule extends AbstractModule {
    private BoostpadDetectorListener detectorListener;

    public DrydockModule() {
        super("drydock");
    }

    @Override
    protected void onModuleEnable() {
        DrydockService drydockService = new DrydockServiceImpl(plugin);
        Wake.getServiceRegistry().register(DrydockService.class, drydockService);
        this.detectorListener = new BoostpadDetectorListener(plugin);
        registerListener(detectorListener);
        registerListener(new OBUBoostpadIntegration());
    }

    @Override
    protected void onModuleDisable() {
        if (detectorListener != null) {
            detectorListener.unregister();
            detectorListener = null;
        }
        if (Wake.getServiceRegistry() != null) {
            Wake.getServiceRegistry().unregister(DrydockService.class);
        }
    }
}
