package io.grayray75.mods.fpsdisplay;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.vehicle.TntMinecartEntity;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.List;

/**
 * Auto crossbow cart (client side only).
 *
 * Right-click a block with a sword in the main hand -> place rail, TNT minecart, fire,
 * then shoot the loaded crossbow through the fire into the cart.
 * Runs as a step machine on END_CLIENT_TICK, one action per tick.
 *
 * A TNT minecart only explodes when hit by a BURNING arrow. The arrow is lit by passing
 * through the fire cell, so every shot is simulated first (blocks in the way, fire cell,
 * cart hit) and only fired when the simulation says "lit hit".
 */
public class FpsTick implements ClientModInitializer {

    private enum Step {
        IDLE, SWAP_IN, PLACE_RAIL, WAIT_RAIL, PLACE_CART, WAIT_CART,
        WAIT_STILL, PLACE_FIRE, WAIT_FIRE, SNEAK, SNEAK_WAIT, SHOOT, RESTORE_SLOT, SWAP_OUT
    }

    private record Plan(BlockPos ground, BlockPos fireSupport) {}

    // Arrow physics (per tick: pos += v; v *= drag; v.y -= gravity)
    private static final double ARROW_SPEED = 3.15;
    private static final double ARROW_DRAG = 0.99;
    private static final double ARROW_GRAVITY = 0.05;
    private static final double SPAWN_DROP = 0.1F; // arrow spawns at eyeY - 0.1F

    private static final int COOLDOWN_TICKS = 20;      // 1 second
    private static final int WAIT_TIMEOUT_TICKS = 40;  // placement verification timeout
    private static final int SNEAK_TICKS = 3;          // ticks to wait after sneaking so the server has the lower eye height
    private static final int SIM_TICKS = 20;           // arrow flight ticks simulated
    private static final double REACH = 4.5;
    private static final double FIRE_MARGIN = 0.1;      // arrow must end a tick this far inside the fire cell
    private static final double MAX_SPEED_SQ = 1.0E-4;  // arrows inherit the shooter's motion, so stand still (strict, checked before fire/shot)
    private static final double START_SPEED_SQ = 4.0E-2; // loose check at click time; the run then waits until the player settles
    private static final int FIRE_CONFIRM_MIN_TICKS = 2; // the fire must survive this long (and at least ping + 1 tick) before shooting,
    private static final int FIRE_CONFIRM_MAX_TICKS = 8; // so a fire the server rejected is noticed instead of wasting the arrow
    private static final double PITCH_MIN = -60.0;
    private static final double PITCH_MAX = 60.0;
    private static final double PITCH_STEP = 0.2;
    private static final int MIN_RUN = 3;               // a usable pitch must have valid neighbours (+-0.2 deg)

    private Step step = Step.IDLE;
    private int cooldown = 0;
    private int waitTicks = 0;
    private int sneakTicks = 0;   // ticks since sneak was pressed
    private int fireAge = 0;      // ticks the fire has been seen
    private int fireConfirmTicks = FIRE_CONFIRM_MIN_TICKS;
    private int hideTooltipTicks = 0;

    private BlockPos groundPos;      // block the rail sits on
    private BlockPos railPos;        // rail cell
    private BlockPos fireSupportPos; // block the fire sits on (next to the rail's support)
    private BlockPos firePos;        // fire cell

    private int origSlot;
    private int railSlot, cartSlot, flintSlot, crossbowSlot;

    private final ArrayDeque<int[]> pendingSwaps = new ArrayDeque<>(); // {inventoryIndex, hotbarSlot}
    private final ArrayDeque<int[]> doneSwaps = new ArrayDeque<>();

    private boolean keepCrossbow = false; // after a real shot: stay on the (now empty) crossbow for reloading
    private boolean sneakEngaged = false;
    private boolean sneakWasPressed = false;

    @Override
    public void onInitializeClient() {
        UseBlockCallback.EVENT.register(this::onUseBlock);
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    // ------------------------------------------------------------------ trigger

    private ActionResult onUseBlock(PlayerEntity player, World world, Hand hand, BlockHitResult hit) {
        if (!world.isClient || hand != Hand.MAIN_HAND) return ActionResult.PASS;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != player || client.world == null || client.currentScreen != null) return ActionResult.PASS;
        if (step != Step.IDLE || cooldown > 0) return ActionResult.PASS;
        if (!player.getMainHandStack().isIn(ItemTags.SWORDS)) return ActionResult.PASS;
        // Let the off hand win: eating, pearls, blocks, shields... anything in the off hand
        // (except a totem, which can't be used) means this click is NOT a combo trigger.
        if (player.isUsingItem()) return ActionResult.PASS;
        ItemStack off = player.getOffHandStack();
        if (!off.isEmpty() && !off.isOf(Items.TOTEM_OF_UNDYING)) return ActionResult.PASS;

        // FAIL cancels the click without sending anything to the server.
        return tryStart(client, client.player, client.world, hit.getBlockPos())
                ? ActionResult.FAIL
                : ActionResult.PASS;
    }

    private static boolean matches(ItemStack stack, Item item) {
        return stack.isOf(item) && (item != Items.CROSSBOW || CrossbowItem.isCharged(stack));
    }

    /** Checks everything up front. Returns false (and changes nothing) if the run cannot start. */
    private boolean tryStart(MinecraftClient client, ClientPlayerEntity player, ClientWorld world, BlockPos clicked) {
        if (client.interactionManager == null) return false;
        // Loose check only: sliding after a stop or a landing tick must not cancel the click.
        // The strict stillness check happens later (WAIT_STILL), where we can simply wait.
        if (player.getVelocity().horizontalLengthSquared() > START_SPEED_SQ) return false;

        PlayerInventory inv = player.getInventory();
        int sword = inv.selectedSlot;

        // order: rail, tnt minecart, flint and steel, loaded crossbow
        Item[] need = {Items.RAIL, Items.TNT_MINECART, Items.FLINT_AND_STEEL, Items.CROSSBOW};
        int[] slot = {-1, -1, -1, -1};   // hotbar slot to use
        int[] source = {-1, -1, -1, -1}; // inventory index to pull from, -1 if already in hotbar
        boolean[] reserved = new boolean[9];
        reserved[sword] = true;

        for (int i = 0; i < 4; i++) {
            int found = -1;
            for (int s = 0; s < 9 && found < 0; s++) {
                if (s != sword && matches(inv.getStack(s), need[i])) found = s;
            }
            if (found >= 0) {
                slot[i] = found;
                reserved[found] = true;
                continue;
            }
            for (int s = 9; s < 36 && found < 0; s++) {
                if (matches(inv.getStack(s), need[i])) found = s;
            }
            if (found < 0) return false; // missing item -> do nothing
            source[i] = found;
        }

        Plan plan = findSpot(player, world, clicked);
        if (plan == null) return false;

        ArrayDeque<int[]> swaps = new ArrayDeque<>();
        for (int i = 0; i < 4; i++) {
            if (source[i] < 0) continue;
            int dest = -1;
            for (int s = 0; s < 9 && dest < 0; s++) {
                if (!reserved[s] && inv.getStack(s).isEmpty()) dest = s; // prefer an empty slot
            }
            for (int s = 0; s < 9 && dest < 0; s++) {
                if (!reserved[s]) dest = s;
            }
            if (dest < 0) return false;
            reserved[dest] = true;
            slot[i] = dest;
            swaps.add(new int[]{source[i], dest});
        }

        // ---- commit
        origSlot = sword;
        railSlot = slot[0];
        cartSlot = slot[1];
        flintSlot = slot[2];
        crossbowSlot = slot[3];
        groundPos = plan.ground();
        railPos = groundPos.up();
        fireSupportPos = plan.fireSupport();
        firePos = fireSupportPos.up();

        pendingSwaps.clear();
        pendingSwaps.addAll(swaps);
        doneSwaps.clear();
        keepCrossbow = false;
        sneakEngaged = false;
        waitTicks = 0;
        step = pendingSwaps.isEmpty() ? Step.PLACE_RAIL : Step.SWAP_IN;

        // A held right mouse button would otherwise keep using whatever slot we select.
        client.options.useKey.setPressed(false);
        return true;
    }

    // ------------------------------------------------------------------ spot search

    /**
     * Click on the 3rd or 4th block away (straight or diagonal): the cart goes on exactly that block.
     * Click on anything else (closer, farther, a wall...): the cart goes on the block 3-4 blocks
     * ahead in the direction the player is looking. Either way the target must already be a solid
     * block (nothing is placed for it) and the fire / aim simulation must pass. If the clicked
     * 3rd/4th block turns out unusable, it falls back to the look-direction search.
     */
    private Plan findSpot(ClientPlayerEntity player, ClientWorld world, BlockPos clicked) {
        Vec3d feet = player.getPos();
        double sneakEyeY = feet.y + player.getEyeHeight(EntityPose.CROUCHING);

        // Offsets in whole blocks from the player's own block, so "3rd / 4th block" doesn't
        // depend on where inside the block you stand. 9..20 = 3 up to about 4.5 blocks away,
        // e.g. (3,0) (4,0) (2,3) (3,3) (4,2) all count, (2,2) and (4,3) do not.
        int dx = clicked.getX() - (int) Math.floor(feet.x);
        int dz = clicked.getZ() - (int) Math.floor(feet.z);
        int distSq = dx * dx + dz * dz;
        if (distSq >= 9 && distSq <= 20 && solidTop(world, clicked) && replaceable(world, clicked.up())) {
            Plan plan = tryGround(player, world, feet, clicked, sneakEyeY);
            if (plan != null) return plan;
        }

        return findAhead(player, world, feet, clicked, sneakEyeY);
    }

    private Plan findAhead(ClientPlayerEntity player, ClientWorld world, Vec3d feet, BlockPos clicked, double sneakEyeY) {
        Vec3d look = player.getRotationVec(1.0F);
        double hx = look.x, hz = look.z;
        double len = Math.hypot(hx, hz);
        if (len < 0.1) {
            hx = clicked.getX() + 0.5 - feet.x;
            hz = clicked.getZ() + 0.5 - feet.z;
            len = Math.hypot(hx, hz);
            if (len < 0.1) return null;
        }
        hx /= len;
        hz /= len;

        double[] distances = {4.0, 4.2, 3.8, 4.4, 3.7, 4.6};
        int[] dys = {0, -1, 1};
        // Ground level is judged from the player's feet, not from the clicked block (which may be a wall or far away).
        int footY = (int) Math.floor(feet.y) - 1;
        int[] bases = clicked.getY() == footY ? new int[]{footY} : new int[]{footY, clicked.getY()};
        for (double d : distances) {
            int bx = (int) Math.floor(feet.x + hx * d);
            int bz = (int) Math.floor(feet.z + hz * d);
            double dd = Math.hypot(bx + 0.5 - feet.x, bz + 0.5 - feet.z);
            if (dd < 3.65 || dd > 4.65) continue;
            for (int base : bases) {
                for (int dy : dys) {
                    BlockPos ground = new BlockPos(bx, base + dy, bz);
                    if (!solidTop(world, ground) || !replaceable(world, ground.up())) continue;
                    Plan plan = tryGround(player, world, feet, ground, sneakEyeY);
                    if (plan != null) return plan;
                }
            }
        }
        return null;
    }

    private Plan tryGround(ClientPlayerEntity player, ClientWorld world, Vec3d feet, BlockPos ground, double sneakEyeY) {
        if (!reachable(player, ground)) return null;

        BlockPos rail = ground.up();
        // estimated cart box (minecart is 0.98 x 0.7, sits 0.0625 above the rail cell floor)
        Vec3d center = new Vec3d(rail.getX() + 0.5, rail.getY() + 0.0625 + 0.35, rail.getZ() + 0.5);
        Box cartBox = new Box(center.x - 0.49, center.y - 0.35, center.z - 0.49,
                center.x + 0.49, center.y + 0.35, center.z + 0.49);
        Vec3d spawn = new Vec3d(feet.x, sneakEyeY - SPAWN_DROP, feet.z);
        double yaw = yawTo(spawn, center);

        double ex = feet.x - center.x;
        double ez = feet.z - center.z;
        int sx = (int) Math.signum(ex);
        int sz = (int) Math.signum(ez);
        int[][] dirs = Math.abs(ex) >= Math.abs(ez)
                ? new int[][]{{sx, 0}, {0, sz}}
                : new int[][]{{0, sz}, {sx, 0}};

        for (int[] dir : dirs) {
            if (dir[0] == 0 && dir[1] == 0) continue;
            BlockPos support = ground.add(dir[0], 0, dir[1]);
            BlockPos fire = support.up();
            if (!solidTop(world, support) || !world.getBlockState(fire).isAir()) continue;
            // sneaking now starts before the fire is placed, so the support must also be in reach from the lowered eyes
            if (!reachable(player, support) || !reachable(player, support, sneakEyeY)) continue;
            // full flight simulation: arrow must be lit by this fire cell and then hit the cart
            if (!hasLitShot(world, player, spawn, yaw, cartBox, fire)) continue;
            return new Plan(ground, support);
        }
        return null;
    }

    private static boolean solidTop(ClientWorld world, BlockPos pos) {
        return world.getBlockState(pos).isSideSolidFullSquare(world, pos, Direction.UP);
    }

    private static boolean replaceable(ClientWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isAir() || (state.isReplaceable() && state.getFluidState().isEmpty());
    }

    private static boolean reachable(ClientPlayerEntity player, BlockPos pos) {
        return reachable(player.getEyePos(), pos);
    }

    /** Reach check as if the eyes were at eyeY (the lowered sneaking eye height). */
    private static boolean reachable(ClientPlayerEntity player, BlockPos pos, double eyeY) {
        return reachable(new Vec3d(player.getX(), eyeY, player.getZ()), pos);
    }

    private static boolean reachable(Vec3d e, BlockPos pos) {
        double dx = Math.max(Math.max(pos.getX() - e.x, 0), e.x - (pos.getX() + 1));
        double dy = Math.max(Math.max(pos.getY() - e.y, 0), e.y - (pos.getY() + 1));
        double dz = Math.max(Math.max(pos.getZ() - e.z, 0), e.z - (pos.getZ() + 1));
        return dx * dx + dy * dy + dz * dz <= REACH * REACH;
    }

    // ------------------------------------------------------------------ aim / arrow simulation

    private static Vec3d direction(double yawDeg, double pitchDeg) {
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        double cp = Math.cos(pitch);
        return new Vec3d(-Math.sin(yaw) * cp, -Math.sin(pitch), Math.cos(yaw) * cp);
    }

    private static double yawTo(Vec3d from, Vec3d to) {
        return Math.toDegrees(Math.atan2(-(to.x - from.x), to.z - from.z));
    }

    private static boolean insideFireCell(Vec3d p, BlockPos fire) {
        return p.x >= fire.getX() + FIRE_MARGIN && p.x <= fire.getX() + 1 - FIRE_MARGIN
                && p.y >= fire.getY() + FIRE_MARGIN && p.y <= fire.getY() + 1 - FIRE_MARGIN
                && p.z >= fire.getZ() + FIRE_MARGIN && p.z <= fire.getZ() + 1 - FIRE_MARGIN;
    }

    /**
     * Simulates one arrow. Returns 0 = blocked / missed, 1 = hits the cart UNLIT (would break it),
     * 2 = hits the cart after passing through the fire cell (explodes).
     * Order per tick matches the game: entity hit and block hit along the movement first,
     * then the arrow moves and fire is checked at the new position.
     */
    private int simulate(ClientWorld world, ClientPlayerEntity player, Vec3d spawn,
                         double yaw, double pitch, Box cartBox, BlockPos fire) {
        Vec3d v = direction(yaw, pitch).multiply(ARROW_SPEED);
        Vec3d pos = spawn;
        boolean lit = false;
        Box hitBox = cartBox.expand(0.3);

        for (int t = 0; t < SIM_TICKS; t++) {
            Vec3d next = pos.add(v);
            BlockHitResult block = world.raycast(new RaycastContext(pos, next,
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
            boolean blocked = block.getType() != HitResult.Type.MISS;
            Vec3d end = blocked ? block.getPos() : next;

            if (hitBox.contains(pos) || hitBox.raycast(pos, end).isPresent()) return lit ? 2 : 1;
            if (blocked) return 0;

            pos = next;
            if (insideFireCell(pos, fire)) lit = true;
            v = v.multiply(ARROW_DRAG).subtract(0, ARROW_GRAVITY, 0);
        }
        return 0;
    }

    /**
     * Scans pitches and returns the middle of the longest run of "lit hit" pitches,
     * so small rounding / cart drift doesn't turn a good shot into a bad one. NaN if none.
     */
    private double findPitch(ClientWorld world, ClientPlayerEntity player, Vec3d spawn,
                             double yaw, Box cartBox, BlockPos fire) {
        int bestStart = -1, bestLen = 0, runStart = -1, runLen = 0;
        int n = (int) Math.round((PITCH_MAX - PITCH_MIN) / PITCH_STEP);
        for (int i = 0; i <= n; i++) {
            double pitch = PITCH_MIN + i * PITCH_STEP;
            if (simulate(world, player, spawn, yaw, pitch, cartBox, fire) == 2) {
                if (runLen == 0) runStart = i;
                runLen++;
                if (runLen > bestLen) { bestLen = runLen; bestStart = runStart; }
            } else {
                runLen = 0;
            }
        }
        if (bestLen < MIN_RUN) return Double.NaN;
        return PITCH_MIN + (bestStart + (bestLen - 1) / 2.0) * PITCH_STEP;
    }

    /**
     * Same answer as "findPitch(...) is not NaN", but stops at the first usable run instead of
     * scanning every pitch. Used by the spot search, which tries many candidates on click.
     */
    private boolean hasLitShot(ClientWorld world, ClientPlayerEntity player, Vec3d spawn,
                               double yaw, Box cartBox, BlockPos fire) {
        int runLen = 0;
        int n = (int) Math.round((PITCH_MAX - PITCH_MIN) / PITCH_STEP);
        for (int i = 0; i <= n; i++) {
            double pitch = PITCH_MIN + i * PITCH_STEP;
            if (simulate(world, player, spawn, yaw, pitch, cartBox, fire) == 2) {
                if (++runLen >= MIN_RUN) return true;
            } else {
                runLen = 0;
            }
        }
        return false;
    }

    /** Best pitch for the live cart and the planned fire cell, NaN if no safe shot exists. */
    private double litAimPitch(ClientWorld world, ClientPlayerEntity player, TntMinecartEntity cart, double eyeY) {
        Vec3d spawn = new Vec3d(player.getX(), eyeY - SPAWN_DROP, player.getZ());
        Box box = cart.getBoundingBox();
        double yaw = yawTo(spawn, box.getCenter());
        return findPitch(world, player, spawn, yaw, box, firePos);
    }

    // ------------------------------------------------------------------ misc helpers

    /** Arrows inherit the shooter's motion, so the player must be standing still on the ground. */
    private static boolean stillEnough(ClientPlayerEntity player) {
        return player.isOnGround() && player.getVelocity().horizontalLengthSquared() <= MAX_SPEED_SQ;
    }

    /** The cart we placed: closest TNT minecart around the rail cell. */
    private TntMinecartEntity findCart(ClientWorld world) {
        if (railPos == null) return null;
        Box area = new Box(railPos).expand(1.0);
        List<TntMinecartEntity> carts = world.getEntitiesByClass(TntMinecartEntity.class, area, e -> true);
        Vec3d rc = Vec3d.ofCenter(railPos);
        TntMinecartEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (TntMinecartEntity c : carts) {
            double d = c.getPos().squaredDistanceTo(rc);
            if (d < bestDist) { bestDist = d; best = c; }
        }
        return best;
    }

    // ------------------------------------------------------------------ hotbar text

    private Field tooltipField;
    private boolean tooltipFieldTried = false;

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

    // ------------------------------------------------------------------ step machine

    private void onTick(MinecraftClient client) {
        if (cooldown > 0) cooldown--;

        // Hide the item-name text above the hotbar that slot switching would show.
        if (step != Step.IDLE) hideTooltipTicks = 3;
        if (hideTooltipTicks > 0) {
            hideTooltipTicks--;
            hideHeldItemTooltip(client);
        }

        if (step == Step.IDLE) return;

        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (player == null || world == null || client.interactionManager == null) {
            reset(client);
            return;
        }

        // Never act while a screen is open. Cleanup waits until it closes.
        if (client.currentScreen != null && step != Step.RESTORE_SLOT) {
            if (step != Step.SWAP_OUT) abort(client);
            return;
        }

        if (sneakEngaged) sneakTicks++;

        // Steps that only wait or check may hand over to the next step in the same tick.
        // Every step that sends something to the server (place rail / cart / fire, shoot) still gets its own tick.
        for (int i = 0; i < 6 && step != Step.IDLE; i++) {
            Step before = step;
            runStep(client, player, world);
            if (step == before || !chainsInto(step)) break;
        }
    }

    private void runStep(MinecraftClient client, ClientPlayerEntity player, ClientWorld world) {
        switch (step) {
            case SWAP_IN -> swapIn(client, player);
            case PLACE_RAIL -> placeRail(client, player);
            case WAIT_RAIL -> waitFor(client, world.getBlockState(railPos).isIn(BlockTags.RAILS), Step.PLACE_CART);
            case PLACE_CART -> placeCart(client, player);
            case WAIT_CART -> waitFor(client, findCart(world) != null, Step.WAIT_STILL);
            case WAIT_STILL -> waitFor(client, stillEnough(player), Step.PLACE_FIRE);
            case PLACE_FIRE -> placeFire(client, player);
            case WAIT_FIRE -> waitFire(client, world);
            case SNEAK -> sneak(client, player);
            case SNEAK_WAIT -> sneakWait(client, player);
            case SHOOT -> shoot(client, player, world);
            case RESTORE_SLOT -> restoreSlot(player);
            case SWAP_OUT -> swapOut(client, player);
            default -> { }
        }
    }

    /** Steps that may run in the same tick as the wait/check step before them. */
    private static boolean chainsInto(Step s) {
        return s == Step.WAIT_STILL || s == Step.PLACE_FIRE || s == Step.SNEAK
                || s == Step.SNEAK_WAIT || s == Step.SHOOT;
    }

    private void swapIn(MinecraftClient client, ClientPlayerEntity player) {
        // Inventory swaps are not world actions, so do them all in one tick (the server handles them in order).
        int[] swap;
        while ((swap = pendingSwaps.poll()) != null) {
            click(client, player, swap[0], swap[1]);
            doneSwaps.push(swap);
        }
        step = Step.PLACE_RAIL;
    }

    private void placeRail(MinecraftClient client, ClientPlayerEntity player) {
        if (!holds(player, railSlot, Items.RAIL)) { abort(client); return; }
        useOnBlock(client, player, railSlot, groundPos);
        waitTicks = 0;
        // The client predicts the placement at once: if the rail already shows, skip the extra wait tick.
        step = client.world != null && client.world.getBlockState(railPos).isIn(BlockTags.RAILS)
                ? Step.PLACE_CART
                : Step.WAIT_RAIL;
    }

    private void placeCart(MinecraftClient client, ClientPlayerEntity player) {
        if (!holds(player, cartSlot, Items.TNT_MINECART)) { abort(client); return; }
        useOnBlock(client, player, cartSlot, railPos);
        // Start sneaking now: the server needs a few ticks to lower the eye height, and we are
        // waiting for the cart to appear anyway, so this costs nothing.
        if (!sneakEngaged) pressSneak(client);
        waitTicks = 0;
        step = Step.WAIT_CART;
    }

    private void placeFire(MinecraftClient client, ClientPlayerEntity player) {
        if (!holds(player, flintSlot, Items.FLINT_AND_STEEL)) { abort(client); return; }
        // Re-check with live positions before placing the fire: the shot must be certain to light the arrow.
        TntMinecartEntity cart = findCart(client.world);
        if (cart == null || !stillEnough(player)
                || Double.isNaN(litAimPitch(client.world, player, cart,
                        player.getY() + player.getEyeHeight(EntityPose.CROUCHING)))) {
            abort(client);
            return;
        }
        useOnBlock(client, player, flintSlot, fireSupportPos);
        waitTicks = 0;
        fireAge = 0;
        fireConfirmTicks = fireConfirmTicks(client, player);
        step = Step.WAIT_FIRE;
    }

    /** Selects the slot and right-clicks the top face of the given block. */
    private void useOnBlock(MinecraftClient client, ClientPlayerEntity player, int hotbarSlot, BlockPos target) {
        player.getInventory().selectedSlot = hotbarSlot;
        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(target).add(0, 0.5, 0), Direction.UP, target, false);
        ActionResult result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
        if (result.shouldSwingHand()) player.swingHand(Hand.MAIN_HAND);
    }

    private void waitFor(MinecraftClient client, boolean ok, Step next) {
        if (ok) {
            step = next;
            waitTicks = 0;
        } else if (++waitTicks > WAIT_TIMEOUT_TICKS) {
            abort(client);
        }
    }

    /**
     * Ticks the fire must be seen before shooting: one server round trip (ping) plus a tick, so a
     * fire the server rejected has time to vanish on the client and the shot is aborted instead.
     */
    private static int fireConfirmTicks(MinecraftClient client, ClientPlayerEntity player) {
        int latencyMs = 0;
        if (client.getNetworkHandler() != null) {
            PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(player.getUuid());
            if (entry != null) latencyMs = entry.getLatency();
        }
        int ticks = (int) Math.ceil(latencyMs / 50.0) + 1;
        return MathHelper.clamp(ticks, FIRE_CONFIRM_MIN_TICKS, FIRE_CONFIRM_MAX_TICKS);
    }

    private void waitFire(MinecraftClient client, ClientWorld world) {
        if (world.getBlockState(firePos).isIn(BlockTags.FIRE)) {
            if (++fireAge >= fireConfirmTicks) {
                step = Step.SNEAK;
                waitTicks = 0;
            }
        } else if (fireAge > 0 || ++waitTicks > WAIT_TIMEOUT_TICKS) {
            abort(client); // the fire vanished (server rejected it) or never showed up
        }
    }

    /**
     * Select the crossbow, then wait until sneak has been held for more than SNEAK_TICKS ticks AND the
     * client really is sneaking. Sneak is pressed back when the cart is placed, so this is normally
     * already satisfied. The server only lowers the eye height on its next tick after it sees the
     * sneak, so the shot must not go out too early or the arrow spawns ~0.35 blocks too high.
     */
    private void sneak(MinecraftClient client, ClientPlayerEntity player) {
        ItemStack bow = player.getInventory().getStack(crossbowSlot);
        if (!bow.isOf(Items.CROSSBOW) || !CrossbowItem.isCharged(bow)) { abort(client); return; }
        player.getInventory().selectedSlot = crossbowSlot;
        if (!sneakEngaged) pressSneak(client); // fallback, normally already pressed at the cart
        step = Step.SNEAK_WAIT;
    }

    private void sneakWait(MinecraftClient client, ClientPlayerEntity player) {
        player.getInventory().selectedSlot = crossbowSlot;
        if (sneakTicks > SNEAK_TICKS && player.isSneaking()) {
            step = Step.SHOOT;
        } else if (sneakTicks > WAIT_TIMEOUT_TICKS) {
            abort(client);
        }
    }

    private void shoot(MinecraftClient client, ClientPlayerEntity player, ClientWorld world) {
        ItemStack bow = player.getInventory().getStack(crossbowSlot);
        TntMinecartEntity cart = findCart(world);
        if (!bow.isOf(Items.CROSSBOW) || !CrossbowItem.isCharged(bow) || cart == null) {
            abort(client);
            return;
        }
        player.getInventory().selectedSlot = crossbowSlot;

        // Only shoot when the fire is still there and the player is still.
        if (!stillEnough(player) || !player.isSneaking()
                || !world.getBlockState(firePos).isIn(BlockTags.FIRE)) { abort(client); return; }

        // Eye height is read live: the player is sneaking right now.
        Vec3d spawn = new Vec3d(player.getX(), player.getEyeY() - SPAWN_DROP, player.getZ());
        Vec3d target = cart.getBoundingBox().getCenter();
        double pitch = litAimPitch(world, player, cart, player.getEyeY());
        // No safe shot (blocked path, cart moved, fire missed) -> don't waste the crossbow charge.
        if (Double.isNaN(pitch)) { abort(client); return; }

        float yaw = (float) MathHelper.wrapDegrees(yawTo(spawn, target));
        float pitchF = (float) pitch;

        player.setYaw(yaw);
        player.setPitch(pitchF);
        player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitchF, player.isOnGround()));
        ActionResult result = client.interactionManager.interactItem(player, Hand.MAIN_HAND);
        if (result.shouldSwingHand()) player.swingHand(Hand.MAIN_HAND);

        releaseSneak(client);
        keepCrossbow = true;
        step = Step.RESTORE_SLOT;
        waitTicks = 0;
    }

    private void restoreSlot(ClientPlayerEntity player) {
        // Keep the crossbow selected for one extra tick so the use packet is processed first.
        if (waitTicks++ < 1) return;
        if (keepCrossbow) {
            // Stay on the crossbow so it can be reloaded right away, and leave it in the hotbar.
            player.getInventory().selectedSlot = crossbowSlot;
            doneSwaps.removeIf(sw -> sw[1] == crossbowSlot);
        } else {
            player.getInventory().selectedSlot = origSlot;
        }
        if (doneSwaps.isEmpty()) finish();
        else step = Step.SWAP_OUT;
    }

    private void swapOut(MinecraftClient client, ClientPlayerEntity player) {
        while (!doneSwaps.isEmpty()) {
            int[] swap = doneSwaps.pop(); // last swap first
            click(client, player, swap[0], swap[1]);
        }
        finish();
    }

    private void finish() {
        step = Step.IDLE;
        cooldown = COOLDOWN_TICKS;
    }

    /** Stops the run: release sneak, then put the slot and swaps back. */
    private void abort(MinecraftClient client) {
        releaseSneak(client);
        pendingSwaps.clear();
        waitTicks = 0;
        step = Step.RESTORE_SLOT;
    }

    private void reset(MinecraftClient client) {
        releaseSneak(client);
        pendingSwaps.clear();
        doneSwaps.clear();
        keepCrossbow = false;
        step = Step.IDLE;
        cooldown = COOLDOWN_TICKS;
    }

    // ------------------------------------------------------------------ inventory / input helpers

    private static boolean holds(ClientPlayerEntity player, int hotbarSlot, Item item) {
        return player.getInventory().getStack(hotbarSlot).isOf(item);
    }

    /** SWAP click between an inventory slot and a hotbar slot (same click undoes itself). */
    private void click(MinecraftClient client, ClientPlayerEntity player, int inventoryIndex, int hotbarSlot) {
        int slotId = inventoryIndex < 9 ? 36 + inventoryIndex : inventoryIndex;
        client.interactionManager.clickSlot(player.playerScreenHandler.syncId, slotId, hotbarSlot, SlotActionType.SWAP, player);
    }

    private void pressSneak(MinecraftClient client) {
        KeyBinding key = client.options.sneakKey;
        sneakEngaged = true;
        sneakTicks = 0;
        sneakWasPressed = key.isPressed();
        if (!key.isPressed()) key.setPressed(true); // in toggle mode this toggles sneak on
    }

    /** Puts the sneak key back to what the physical key is doing. */
    private void releaseSneak(MinecraftClient client) {
        if (!sneakEngaged) return;
        sneakEngaged = false;
        KeyBinding key = client.options.sneakKey;
        if (client.options.getSneakToggled().getValue()) {
            if (key.isPressed() && !sneakWasPressed) key.setPressed(true); // toggles back off
        } else {
            key.setPressed(sneakWasPressed);
        }
    }
}
