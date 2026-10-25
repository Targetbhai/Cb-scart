package io.grayray75.mods.fpsdisplay;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * One-click cart mode. Right-click a block while holding a sword.
 * Sequence (same as the original mod):
 *   rail -> TNT minecart -> fire (flint & steel) -> [slab if you carry one] -> crossbow shot
 * After the shot you stay on the crossbow so you can reload right away.
 * Items can be anywhere in your inventory (hotbar or main). Ones that are not in the
 * hotbar are swapped in temporarily and swapped back afterwards.
 * Safe mode is automatic: it is only active when you have an oak slab in your inventory.
 *
 * The click is only cancelled when the combo really starts. Everything else (chests, doors,
 * respawn anchors, eating with the off hand, missing items, bad target...) behaves like vanilla.
 * Nothing is written above the hotbar.
 */
public class FpsTick implements ClientModInitializer {

    private static final double RANGE = 5.0;
    private static final long SHOOT_DELAY_MS = 150;     // wait before shooting so rail, cart and fire are placed
    private static final long STUCK_TIMEOUT_MS = 2000;  // safety net: a run can never block clicks longer than this

    private volatile boolean running = false;
    private volatile long runStartedMs = 0;
    private int hideTicks = 0;

    @Override
    public void onInitializeClient() {
        UseBlockCallback.EVENT.register(this::onUseBlock);
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    // ---------------------------------------------------------------- trigger

    private ActionResult onUseBlock(PlayerEntity p, World w, Hand hand, BlockHitResult hit) {
        if (!w.isClient() || hand != Hand.MAIN_HAND) return ActionResult.PASS;
        if (!(p instanceof ClientPlayerEntity player) || !(w instanceof ClientWorld world)) return ActionResult.PASS;
        if (!player.getMainHandStack().isIn(ItemTags.SWORDS)) return ActionResult.PASS;

        // A run is in progress: swallow the click so it can't interfere. Never for longer than the timeout,
        // so a failed run can't leave sword clicks blocked.
        if (running) {
            if (System.currentTimeMillis() - runStartedMs <= STUCK_TIMEOUT_MS) return ActionResult.FAIL;
            running = false;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen != null) return ActionResult.PASS;

        // Off hand wins: eating (golden apple...), pearls, shield, blocks... means this is NOT a combo click.
        // A totem can't be used, so it doesn't count.
        if (player.isUsingItem()) return ActionResult.PASS;
        ItemStack off = player.getOffHandStack();
        if (!off.isEmpty() && !off.isOf(Items.TOTEM_OF_UNDYING)) return ActionResult.PASS;

        // Blocks that do something when right-clicked (chest, door, respawn anchor, ...) keep working.
        BlockPos clicked = hit.getBlockPos();
        if (isInteractive(world.getBlockState(clicked), world, clicked)) return ActionResult.PASS;

        BlockPos target = clicked.up();
        if (!isValidTarget(player, world, target)) return ActionResult.PASS;

        // Only cancel the vanilla click if the combo really started.
        return execute(player, world, target) ? ActionResult.FAIL : ActionResult.PASS;
    }

    /** Blocks the player expects to interact with when right-clicking. */
    private boolean isInteractive(BlockState state, ClientWorld world, BlockPos pos) {
        if (state.createScreenHandlerFactory(world, pos) != null) return true; // chests, furnaces, tables, shulkers...
        if (state.isIn(BlockTags.DOORS) || state.isIn(BlockTags.TRAPDOORS) || state.isIn(BlockTags.BUTTONS)
                || state.isIn(BlockTags.FENCE_GATES) || state.isIn(BlockTags.BEDS)
                || state.isIn(BlockTags.ANVIL) || state.isIn(BlockTags.ALL_SIGNS)) return true;
        return state.isOf(Blocks.RESPAWN_ANCHOR) || state.isOf(Blocks.LEVER) || state.isOf(Blocks.NOTE_BLOCK)
                || state.isOf(Blocks.REPEATER) || state.isOf(Blocks.COMPARATOR) || state.isOf(Blocks.DAYLIGHT_DETECTOR)
                || state.isOf(Blocks.BELL) || state.isOf(Blocks.LECTERN) || state.isOf(Blocks.JUKEBOX)
                || state.isOf(Blocks.COMPOSTER) || state.isOf(Blocks.DRAGON_EGG);
    }

    private boolean isValidTarget(ClientPlayerEntity player, ClientWorld world, BlockPos target) {
        double dist = Vec3d.ofCenter(player.getBlockPos()).distanceTo(Vec3d.ofCenter(target));
        if (dist > RANGE) return false;
        BlockPos below = target.down();
        if (!world.getBlockState(below).isSolidBlock(world, below)) return false;
        return world.getBlockState(target).isAir();
    }

    // --------------------------------------------------------------- sequence

    /** Returns true if the combo started (the click must then be cancelled), false if nothing was done. */
    private boolean execute(ClientPlayerEntity player, ClientWorld world, BlockPos target) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.interactionManager == null) return false;

        // what we need, searched in the whole inventory (hotbar + main, slots 0-35)
        Predicate<ItemStack> railPred = existsAnywhere(player, s -> s.isOf(Items.RAIL))
                ? s -> s.isOf(Items.RAIL)
                : s -> s.isIn(ItemTags.RAILS);
        Predicate<ItemStack> cartPred = s -> s.isOf(Items.TNT_MINECART);
        Predicate<ItemStack> flintPred = s -> s.isOf(Items.FLINT_AND_STEEL);
        Predicate<ItemStack> slabPred = s -> s.isOf(Items.OAK_SLAB);
        Predicate<ItemStack> bowPred = s -> s.isOf(Items.CROSSBOW) && CrossbowItem.isCharged(s);

        // Missing something: do nothing and let the click behave normally.
        if (!existsAnywhere(player, bowPred)) return false;
        if (!existsAnywhere(player, railPred)) return false;
        if (!existsAnywhere(player, cartPred)) return false;
        if (!existsAnywhere(player, flintPred)) return false;

        // Safe mode: automatically on only if you carry an oak slab anywhere in your inventory
        boolean safeMode = existsAnywhere(player, slabPred);

        BlockPos firePos = calcFirePos(player, world, target);
        if (firePos == null) return false;

        int originalSlot = player.getInventory().selectedSlot;
        List<int[]> swaps = new ArrayList<>(); // {inventorySlot, hotbarSlot}
        Set<Integer> reserved = new HashSet<>();
        reserved.add(originalSlot); // never touch the sword slot

        int finalBowSlot;
        running = true;
        runStartedMs = System.currentTimeMillis();
        hideTicks = 10;
        try {
            // Pass 1: reserve hotbar slots that already hold what we need
            int[] slots = new int[5];
            @SuppressWarnings("unchecked")
            Predicate<ItemStack>[] preds = new Predicate[]{railPred, cartPred, flintPred, slabPred, bowPred};
            for (int k = 0; k < 5; k++) {
                slots[k] = -1;
                if (k == 3 && !safeMode) continue;
                for (int i = 0; i < 9; i++) {
                    if (i != originalSlot && !reserved.contains(i) && preds[k].test(player.getInventory().getStack(i))) {
                        slots[k] = i;
                        reserved.add(i);
                        break;
                    }
                }
            }
            // Pass 2: bring missing ones from the main inventory into free hotbar slots
            for (int k = 0; k < 5; k++) {
                if (slots[k] != -1 || (k == 3 && !safeMode)) continue;
                int from = -1;
                for (int j = 9; j < 36; j++) {
                    if (preds[k].test(player.getInventory().getStack(j))) { from = j; break; }
                }
                int to = -1;
                for (int h = 0; h < 9; h++) {
                    if (!reserved.contains(h)) { to = h; break; }
                }
                if (from == -1 || to == -1) {
                    if (k != 3) throw new IllegalStateException("no slot for item " + k);
                    continue;
                }
                mc.interactionManager.clickSlot(player.playerScreenHandler.syncId, from, to, SlotActionType.SWAP, player);
                swaps.add(new int[]{from, to});
                reserved.add(to);
                slots[k] = to;
            }
            int railSlot = slots[0], cartSlot = slots[1], flintSlot = slots[2], slabSlot = slots[3], bowSlot = slots[4];

            // 1. rail on the target
            select(mc, player, railSlot);
            rotateAndSendPacket(player, target);
            clickOnBlockBelow(mc, player, target);

            // 2. TNT minecart on the rail
            select(mc, player, cartSlot);
            rotateAndSendPacket(player, target);
            clickOnBlock(mc, player, target);

            // 3. fire next to the target (between you and it)
            select(mc, player, flintSlot);
            rotateAndSendPacket(player, firePos);
            clickOnBlockBelow(mc, player, firePos);

            // 4. safe mode: slab behind the fire, toward you
            if (safeMode && slabSlot != -1) {
                BlockPos safePos = calcSafePos(player, world, firePos);
                if (safePos != null) {
                    select(mc, player, slabSlot);
                    rotateAndSendPacket(player, safePos);
                    clickOnBlockBelow(mc, player, safePos);
                }
            }

            // 5. crossbow: select it and aim at the target (the shot happens in step 6)
            select(mc, player, bowSlot);
            rotateAndSendPacket(player, target);
            finalBowSlot = bowSlot;
        } catch (Throwable e) {
            // Something went wrong half way: put everything back and never stay in "running".
            try {
                restore(mc, player, originalSlot, swaps, -1, true);
            } catch (Throwable ignored) {
            } finally {
                running = false;
            }
            return true; // packets may already be out, so the click still counts as handled
        }

        // 6. after a short delay: aim again, fire the crossbow, and STAY on the crossbow
        final int bowSlotF = finalBowSlot;
        try {
            Thread t = new Thread(() -> {
                try { Thread.sleep(SHOOT_DELAY_MS); } catch (InterruptedException ignored) {}
                try {
                    mc.execute(() -> {
                        try {
                            if (mc.interactionManager != null && mc.player == player) {
                                select(mc, player, bowSlotF);
                                rotateAndSendPacket(player, target);
                                mc.interactionManager.interactItem(player, Hand.MAIN_HAND); // fires the charged crossbow
                                player.swingHand(Hand.MAIN_HAND);
                            }
                            // put swapped items back, but keep the crossbow where it is and keep it selected
                            restore(mc, player, originalSlot, swaps, bowSlotF, false);
                        } catch (Throwable ignored) {
                        } finally {
                            hideTicks = 10;
                            running = false; // always released, even if something above threw
                        }
                    });
                } catch (Throwable ignored) {
                    running = false;
                }
            }, "FpsTick");
            t.setDaemon(true);
            t.start();
        } catch (Throwable e) {
            try {
                restore(mc, player, originalSlot, swaps, -1, true);
            } catch (Throwable ignored) {
            } finally {
                running = false;
            }
        }
        return true;
    }

    private void restore(MinecraftClient mc, ClientPlayerEntity player, int originalSlot, List<int[]> swaps,
                         int bowSlot, boolean backToSword) {
        if (backToSword) select(mc, player, originalSlot);
        if (mc.interactionManager == null) return;
        for (int i = swaps.size() - 1; i >= 0; i--) {
            int[] sw = swaps.get(i);
            if (!backToSword && sw[1] == bowSlot) continue; // leave the crossbow in the hotbar
            mc.interactionManager.clickSlot(player.playerScreenHandler.syncId, sw[0], sw[1], SlotActionType.SWAP, player);
        }
    }

    // ----------------------------------------------------------- position math

    /** Block next to the target on the side facing you. Must be air. */
    private BlockPos calcFirePos(ClientPlayerEntity player, ClientWorld world, BlockPos target) {
        Vec3d dir = Vec3d.ofCenter(target).subtract(Vec3d.ofCenter(player.getBlockPos())).normalize();
        BlockPos pos;
        if (Math.abs(dir.x) > Math.abs(dir.z)) {
            pos = target.offset(dir.x > 0 ? Direction.WEST : Direction.EAST);
        } else {
            pos = target.offset(dir.z > 0 ? Direction.NORTH : Direction.SOUTH);
        }
        return world.getBlockState(pos).isAir() ? pos : null;
    }

    /** Block one step from the fire toward you, at your feet height. Must be air. */
    private BlockPos calcSafePos(ClientPlayerEntity player, ClientWorld world, BlockPos firePos) {
        Vec3d dir = Vec3d.ofCenter(player.getBlockPos()).subtract(Vec3d.ofCenter(firePos)).normalize();
        BlockPos pos;
        if (Math.abs(dir.x) > Math.abs(dir.z)) {
            pos = firePos.offset(dir.x > 0 ? Direction.EAST : Direction.WEST);
        } else {
            pos = firePos.offset(dir.z > 0 ? Direction.SOUTH : Direction.NORTH);
        }
        pos = new BlockPos(pos.getX(), player.getBlockPos().getY(), pos.getZ());
        return world.getBlockState(pos).isAir() ? pos : null;
    }

    // ------------------------------------------------------------ interactions

    private void clickOnBlock(MinecraftClient mc, ClientPlayerEntity player, BlockPos pos) {
        if (mc.interactionManager == null) return;
        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos).add(0, 0.5, 0), Direction.UP, pos, false);
        mc.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
        player.swingHand(Hand.MAIN_HAND);
    }

    private void clickOnBlockBelow(MinecraftClient mc, ClientPlayerEntity player, BlockPos pos) {
        clickOnBlock(mc, player, pos.down());
    }

    private void rotateAndSendPacket(ClientPlayerEntity player, BlockPos pos) {
        Vec3d target = Vec3d.ofCenter(pos);
        Vec3d eye = player.getCameraPosVec(1.0F);
        Vec3d dir = target.subtract(eye).normalize();
        double yaw = Math.toDegrees(Math.atan2(-dir.x, dir.z));
        double pitch = MathHelper.clamp(-Math.toDegrees(Math.asin(dir.y)), -90.0, 90.0);
        player.setYaw((float) yaw);
        player.setPitch((float) pitch);
        if (MinecraftClient.getInstance().getNetworkHandler() != null) {
            MinecraftClient.getInstance().getNetworkHandler().sendPacket(
                    new PlayerMoveC2SPacket.Full(player.getX(), player.getY(), player.getZ(),
                            (float) yaw, (float) pitch, player.isOnGround()));
        }
    }

    // ----------------------------------------------------------------- items

    private void select(MinecraftClient mc, ClientPlayerEntity player, int slot) {
        player.getInventory().selectedSlot = slot;
        if (mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        }
    }

    private boolean existsAnywhere(ClientPlayerEntity player, Predicate<ItemStack> pred) {
        for (int i = 0; i < 36; i++) {
            if (pred.test(player.getInventory().getStack(i))) return true;
        }
        return false;
    }

    // ------------------------------------------------------------ hotbar text

    private Field tooltipField;
    private boolean tooltipFieldTried = false;

    /** Every tick: while a run is going (and shortly after) keep the item-name text above the hotbar hidden. */
    private void onTick(MinecraftClient client) {
        if (running) hideTicks = Math.max(hideTicks, 3);
        if (hideTicks <= 0) return;
        hideTicks--;
        hideHeldItemTooltip(client);
    }

    /**
     * Best effort, single-file (no mixin): zeroes the HUD's held-item-name timer through reflection.
     * If the field can't be found it silently does nothing.
     */
    private void hideHeldItemTooltip(MinecraftClient client) {
        if (client.inGameHud == null) return;
        if (!tooltipFieldTried) {
            tooltipFieldTried = true;
            for (String name : new String[]{"heldItemTooltipFade", "field_2016"}) {
                try {
                    Field f = InGameHud.class.getDeclaredField(name);
                    f.setAccessible(true);
                    tooltipField = f;
                    break;
                } catch (Throwable ignored) { }
            }
        }
        if (tooltipField == null) return;
        try {
            tooltipField.setInt(client.inGameHud, 0);
        } catch (Throwable ignored) { }
    }
}
