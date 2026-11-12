package com.hibiscusmc.hmccosmetics.util;

import com.github.Anon8281.universalScheduler.scheduling.schedulers.TaskScheduler;
import com.hibiscusmc.hmccosmetics.HMCCosmeticsPlugin;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class HMCCServerUtilsTest {
    @Test
    void zeroDelayUsesImmediateEntityScheduler() {
        Entity entity = mock(Entity.class);
        EntityScheduler entityScheduler = mock(EntityScheduler.class);
        ScheduledTask scheduledTask = mock(ScheduledTask.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        HMCCosmeticsPlugin plugin = mock(HMCCosmeticsPlugin.class);
        AtomicBoolean ran = new AtomicBoolean();

        when(entity.getScheduler()).thenReturn(entityScheduler);
        // Folia's runDelayed throws for a zero delay, so the task must go through run() instead.
        when(entityScheduler.run(eq(plugin), any(), any())).thenAnswer(invocation -> {
            invocation.<Consumer<ScheduledTask>>getArgument(1).accept(scheduledTask);
            return scheduledTask;
        });
        when(entityScheduler.runDelayed(any(), any(), any(), anyLong()))
                .thenThrow(new IllegalArgumentException("Delay ticks may not be <= 0"));

        try (MockedStatic<HMCCosmeticsPlugin> plugins = mockStatic(HMCCosmeticsPlugin.class)) {
            plugins.when(HMCCosmeticsPlugin::getScheduler).thenReturn(scheduler);
            plugins.when(HMCCosmeticsPlugin::getInstance).thenReturn(plugin);

            HMCCServerUtils.scheduleOnEntity(entity, () -> ran.set(true), 0L, 0);

            assertTrue(ran.get());
            verify(entityScheduler).run(eq(plugin), any(), any());
            verify(entityScheduler, never()).runDelayed(any(), any(), any(), anyLong());
        }
    }
}
