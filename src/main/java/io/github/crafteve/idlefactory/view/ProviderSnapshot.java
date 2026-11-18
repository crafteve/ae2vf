package io.github.crafteve.idlefactory.view;

import io.github.crafteve.idlefactory.io.BlockingMode;
import io.github.crafteve.idlefactory.view.ProviderSnapshot;
import io.github.crafteve.idlefactory.io.DispatchMode;
import io.github.crafteve.idlefactory.node.Multiplier;

public record ProviderSnapshot(boolean available, BlockingMode blocking, Multiplier multiplier,
        DispatchMode dispatch) {
}
