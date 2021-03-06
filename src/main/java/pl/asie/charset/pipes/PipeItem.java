package pl.asie.charset.pipes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;

import pl.asie.charset.api.lib.IItemInjectable;
import pl.asie.charset.api.pipes.IShifter;
import pl.asie.charset.lib.DirectionUtils;
import pl.asie.charset.lib.ItemUtils;
import pl.asie.charset.lib.inventory.InventoryUtils;

public class PipeItem {
	public static final int MAX_PROGRESS = 128;
	public static final int CENTER_PROGRESS = MAX_PROGRESS / 2;
	public static final int SPEED = MAX_PROGRESS / 16;
	private static short nextId;

	public final short id;
	private int activeShifterDistance;
	private TilePipe owner;
	private boolean stuck;

	protected EnumFacing input, output;
	protected boolean reachedCenter;
	protected ItemStack stack;
	protected int progress;
	protected int blocksSinceSync;

	public PipeItem(TilePipe owner, ItemStack stack, EnumFacing side) {
		this.id = nextId++;
		this.owner = owner;
		this.stack = stack;
		initializeFromEntrySide(side);
	}

	public PipeItem(TilePipe owner, NBTTagCompound nbt) {
		this.id = nextId++;
		this.owner = owner;
		readFromNBT(nbt);
	}

	protected PipeItem(TilePipe tile, short id) {
		this.owner = tile;
		this.id = id;
	}

	public boolean isStuck() {
		return stuck;
	}

	public boolean isValid() {
		return stack != null && stack.getItem() != null && input != null;
	}

	private float getTranslatedCoord(int offset) {
		if (progress >= CENTER_PROGRESS) {
			return 0.5F + (float) offset * (progress - CENTER_PROGRESS) / MAX_PROGRESS;
		} else {
			switch (offset) {
				case -1:
					return 1.0F + (float) offset * progress / MAX_PROGRESS;
				case 0:
				default:
					return 0.5F;
				case 1:
					return (float) offset * progress / MAX_PROGRESS;
			}
		}
	}

	public float getX() {
		return getDirection() != null ? getTranslatedCoord(getDirection().getFrontOffsetX()) : 0.5F;
	}

	public float getY() {
		return getDirection() != null ? getTranslatedCoord(getDirection().getFrontOffsetY()) : 0.5F;
	}

	public float getZ() {
		return getDirection() != null ? getTranslatedCoord(getDirection().getFrontOffsetZ()) : 0.5F;
	}

	public ItemStack getStack() {
		return stack;
	}

	public EnumFacing getDirection() {
		return reachedCenter ? output : (input != null ? input.getOpposite() : null);
	}

	private boolean isCentered() {
		return progress == CENTER_PROGRESS;
	}

	// This version takes priority into account (filtered shifters are
	// prioritized over unfiltered shifters at the same distance).
	private int getInternalShifterStrength(IShifter shifter, EnumFacing dir) {
		if (shifter == null) {
			return 0;
		} else {
			return owner.getShifterStrength(dir) * 2 + (shifter.hasFilter() ? 0 : 1);
		}
	}

	private void updateStuckFlag() {
		if (progress <= CENTER_PROGRESS) {
			boolean needsRecalculation = false;

			if (!isValidDirection(output)) {
				needsRecalculation = true;
			} else if (stuck && isCentered()) {
				// Detect changes in shifter air stream.
				boolean foundShifter = false;
				int minimumShifterDistance = Integer.MAX_VALUE;

				// Find the closest shifter affecting the item.
				for (EnumFacing dir : EnumFacing.VALUES) {
					IShifter p = owner.getNearestShifter(dir);
					int ps = getInternalShifterStrength(p, dir);
					if (ps > 0 && ps < minimumShifterDistance
							&& isShifterPushing(p, output)) {
						minimumShifterDistance = ps;
						foundShifter = true;
					}
				}

				if (
						(!foundShifter && activeShifterDistance > 0)
						||	(foundShifter && activeShifterDistance != minimumShifterDistance)
						||	(foundShifter && activeShifterDistance != getInternalShifterStrength(owner.getNearestShifter(output), output))
				) {
					TileEntity shifterTile = owner.getWorld().getTileEntity(owner.getPos().offset(output.getOpposite(), activeShifterDistance));

					if (!(shifterTile instanceof IShifter) || !isShifterPushing((IShifter) shifterTile, output)) {
						needsRecalculation = true;
					}
				}
			}

			if (needsRecalculation) {
				calculateOutputDirection();
			}
		} else {
			if (!isValidDirection(output)) {
				output = null;
			}
		}

		if (output == null) {
			// Never stuck when UNKNOWN, because the item will drop anyway.
			stuck = false;
		} else {
			if (isCentered() && activeShifterDistance > 0
					&& owner.getShifterStrength(output.getOpposite()) == owner.getShifterStrength(output)
					&& isShifterPushing(owner.getNearestShifter(output.getOpposite()), output.getOpposite())) {
				// Handle the "equal-distance opposite shifters" scenario for stopping items.
				// This does not take filtering into account!
				stuck = true;
			} else {
				stuck = !canMoveDirection(output, false);
			}
		}
	}

	protected void sendPacket(boolean syncStack) {
		if (owner.getWorld() != null && !owner.getWorld().isRemote) {
			ModCharsetPipes.packet.sendToAllAround(new PacketItemUpdate(owner, this, syncStack), owner, ModCharsetPipes.PIPE_TESR_DISTANCE);
		}
	}

	public boolean move() {
		if (!reachedCenter) {
			boolean atCenter = (progress + SPEED) >= CENTER_PROGRESS;

			if (atCenter) {
				onReachedCenter();
			} else {
				progress += SPEED;
			}
		} else {
			if (owner.getWorld().isRemote) {
				if (!stuck) {
					progress += SPEED;
				}

				if (progress >= MAX_PROGRESS) {
					onItemEnd();
					return false;
				}
			} else {
				EnumFacing oldOutput = output;
				boolean oldStuck = stuck;

				updateStuckFlag();

				if (!stuck) {
					progress += SPEED;
				}

				if (progress >= MAX_PROGRESS) {
					onItemEnd();
					return false;
				}

				if (oldStuck != stuck || oldOutput != output) {
					sendPacket(false);
				}
			}
		}

		return true;
	}

	private void onItemEnd() {
		TileEntity tile = owner.getNeighbourTile(output);

		if (owner.getWorld().isRemote) {
			// Last resort security mechanism for stray packets.
			blocksSinceSync++;

			if (blocksSinceSync < 2) {
				passToPipe(tile, output, false);
			}
			return;
		}

		if (output != null) {
			if (passToPipe(tile, output, false)) {
				// Pipe passing does not take into account stack size
				// subtraction, as it re-uses the same object instance.
				// Therefore, we need to quit here.
				return;
			} else {
				if (!passToInjectable(tile, output, false)) {
					addToInventory(tile, output, false);
				}
			}
		}

		if (stack != null && stack.stackSize > 0) {
			dropItem(true);
		}
	}

	private boolean isValidDirection(EnumFacing dir) {
		if (dir == null || !owner.connects(dir)) {
			return false;
		}

		TileEntity tile = owner.getNeighbourTile(dir);

		if (tile instanceof IItemInjectable) {
			return ((IItemInjectable) tile).canInjectItems(dir.getOpposite());
		} else if (tile instanceof IInventory) {
			return InventoryUtils.connects((IInventory) tile, dir.getOpposite());
		}

		return false;
	}

	private boolean canMoveDirection(EnumFacing dir, boolean isPickingDirection) {
		if (dir == null) {
			return activeShifterDistance == 0;
		}

		TileEntity tile = owner.getNeighbourTile(dir);

		/* if (isPickingDirection) {
			// If we're picking the direction, only check for pipe *connection*,
			// so that clogging mechanics (pipes which can't take in items) work
			// as intended.
			if (tile instanceof TilePipe) {
				if (((TilePipe) tile).connects(dir.getOpposite())) {
					return true;
				}
			}
		} else { */
		if (passToPipe(tile, dir, true)) {
			return true;
		}
		// }

		if (passToInjectable(tile, dir, true)) {
			return true;
		}

		if (addToInventory(tile, dir, true)) {
			return true;
		}

		return false;
	}

	private boolean isShifterPushing(IShifter p, EnumFacing direction) {
		return p != null
				&& p.getDirection() == direction
				&& p.isShifting()
				&& p.matches(stack);
	}

	private void calculateOutputDirection() {
		if (owner.getWorld() == null || owner.getWorld().isRemote) {
			return;
		}

		List<EnumFacing> directionList = new ArrayList<EnumFacing>();
		List<EnumFacing> pressureList = new ArrayList<EnumFacing>();

		activeShifterDistance = 0;

		// Step 1: Make a list of all valid directions, as well as all shifters.
		for (EnumFacing direction : EnumFacing.VALUES) {
			if (isValidDirection(direction)) {
				directionList.add(direction);
			}

			IShifter p = owner.getNearestShifter(direction);

			if (isShifterPushing(p, direction)) {
				pressureList.add(direction);
			}
		}

		// Step 2: Sort the shifter list.
		Collections.sort(pressureList, new Comparator<EnumFacing>() {
			@Override
			public int compare(EnumFacing o1, EnumFacing o2) {
				return getInternalShifterStrength(owner.getNearestShifter(o1), o1) - getInternalShifterStrength(owner.getNearestShifter(o2), o2);
			}
		});


		EnumFacing firstOutput = null;

		// Step 3: Pick the next path.
		for (EnumFacing dir : pressureList) {
			if (canMoveDirection(dir, true)) {
				this.output = dir;
				activeShifterDistance = getInternalShifterStrength(owner.getNearestShifter(dir), dir);
				return;
			} else if (firstOutput == null && isValidDirection(dir)) {
				firstOutput = dir;
			}
		}

		directionList.removeAll(pressureList);
		directionList.remove(input);

		if (directionList.size() > 0) {
			EnumFacing dir;
			int i = 0;

			// Step 3b: Pick the first "unforced" direction to scan.
			// A direction opposite to input (aka. "straight line")
			// takes priority.
			if (directionList.contains(input.getOpposite())) {
				dir = input.getOpposite();
				directionList.remove(input.getOpposite());
			} else if (directionList.size() > 0) {
				Collections.shuffle(directionList);
				dir = directionList.get(0);
				i = 1;
			} else {
				this.output = null;
				return;
			}

			if (firstOutput == null) {
				firstOutput = dir;
			}

			while (!canMoveDirection(dir, true) && i < directionList.size()) {
				dir = directionList.get(i);
				i++;
			}

			// Step 3c: If a valid, free direction has been found, use that.
			// Otherwise, set it to the first output direction selected to
			// prioritize that, for some reason.
			if (canMoveDirection(dir, true)) {
				this.output = dir;
			} else {
				this.output = firstOutput;
			}
		} else {
			this.output = firstOutput;
		}
	}

	private void onReachedCenter() {
		progress = CENTER_PROGRESS;
		this.reachedCenter = true;

		if (owner.getWorld().isRemote) {
			return;
		}

		calculateOutputDirection();
		updateStuckFlag();
		sendPacket(false);
	}

	protected void reset(TilePipe owner, EnumFacing input) {
		this.owner = owner;
		initializeFromEntrySide(input);

		// Do an early calculation to aid the server side.
		// Won't always be right, might be sometimes right.
		calculateOutputDirection();
	}

	private boolean passToInjectable(TileEntity tile, EnumFacing dir, boolean simulate) {
		if (tile instanceof IItemInjectable && !(tile instanceof TilePipe)) {
			int added = ((IItemInjectable) tile).injectItem(stack, dir.getOpposite(), simulate);
			if (added > 0) {
				if (!simulate) {
					stack.stackSize -= added;
				}
				return true;
			}
		}

		return false;
	}

	private boolean passToPipe(TileEntity tile, EnumFacing dir, boolean simulate) {
		if (tile instanceof TilePipe) {
			if (((TilePipe) tile).injectItemInternal(this, dir.getOpposite(), simulate)) {
				return true;
			}
		}

		return false;
	}

	private boolean addToInventory(TileEntity tile, EnumFacing dir, boolean simulate) {
		if (tile instanceof IInventory) {
			int added = InventoryUtils.addStack((IInventory) tile, dir.getOpposite(), stack, simulate);
			if (added > 0) {
				if (!simulate) {
					stack.stackSize -= added;
				}
				return true;
			}
		}

		return false;
	}

	protected void dropItem(boolean useOutputDirection) {
		EnumFacing dir = null;

		if (useOutputDirection) {
			// Decide output direction
			int directions = 0;
			for (EnumFacing d : EnumFacing.VALUES) {
				if (owner.connects(d)) {
					directions++;
					dir = d.getOpposite();
					if (directions >= 2) {
						break;
					}
				}
			}

			if (directions >= 2) {
				dir = null;
			}
		}

		ItemUtils.spawnItemEntity(owner.getWorld(),
				(double) owner.getPos().getX() + 0.5 + (dir != null ? dir.getFrontOffsetX() : 0) * 0.75,
				(double) owner.getPos().getY() + 0.5 + (dir != null ? dir.getFrontOffsetY() : 0) * 0.75,
				(double) owner.getPos().getZ() + 0.5 + (dir != null ? dir.getFrontOffsetZ() : 0) * 0.75,
				stack, 0, 0, 0);

		stack = null;
	}

	private void initializeFromEntrySide(EnumFacing side) {
		this.input = side;
		this.output = null;
		this.reachedCenter = false;
		this.stuck = false;
		this.progress = 0;
	}

	public void readFromNBT(NBTTagCompound nbt) {
		stack = ItemStack.loadItemStackFromNBT(nbt);
		progress = nbt.getShort("p");
		input = DirectionUtils.get(nbt.getByte("in"));
		output = DirectionUtils.get(nbt.getByte("out"));
		reachedCenter = nbt.getBoolean("reachedCenter");
		if (nbt.hasKey("stuck")) {
			stuck = nbt.getBoolean("stuck");
		}
		if (nbt.hasKey("activePD")) {
			activeShifterDistance = nbt.getInteger("activePD");
		}
	}

	public void writeToNBT(NBTTagCompound nbt) {
		stack.writeToNBT(nbt);
		nbt.setShort("p", (short) progress);
		nbt.setByte("in", (byte) DirectionUtils.ordinal(input));
		nbt.setByte("out", (byte) DirectionUtils.ordinal(output));
		nbt.setBoolean("reachedCenter", reachedCenter);
		if (stuck) {
			nbt.setBoolean("stuck", stuck);
		}
		if (activeShifterDistance > 0) {
			nbt.setInteger("activePD", activeShifterDistance);
		}
	}

	public boolean hasReachedCenter() {
		return reachedCenter;
	}

	public void setStuckFlagClient(boolean stuck) {
		if (owner.getWorld().isRemote) {
			this.stuck = stuck;
		}
	}

	public TilePipe getOwner() {
		return owner;
	}

	public float getProgress() {
		return (float) progress / MAX_PROGRESS;
	}
}
