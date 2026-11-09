package dev.skycore;

import dev.skycore.core.Skycore;
import net.fabricmc.api.ClientModInitializer;

public class SkycoreClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Skycore.init();
    }
}
