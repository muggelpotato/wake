package dev.muggel.wake.features.drydock;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.module.AbstractModule;
import dev.muggel.wake.features.drydock.api.DrydockService;
import dev.muggel.wake.features.drydock.service.DrydockServiceImpl;

public class DrydockModule extends AbstractModule {

    public DrydockModule() {
        super("drydock");
    }

    @Override
    protected void onModuleEnable() {
        DrydockService drydockService = new DrydockServiceImpl(plugin);
        Wake.getServiceRegistry().register(DrydockService.class, drydockService);
    }

    @Override
    protected void onModuleDisable() {
        if (Wake.getServiceRegistry() != null) {
            Wake.getServiceRegistry().unregister(DrydockService.class);
        }
    }
}
