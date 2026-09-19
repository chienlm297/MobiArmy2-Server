package mobiarmy.server;

import java.util.ArrayList;
import java.util.HashMap;
import mobiarmy.Util;
import mobiarmy.war.Boss.bullet.BulletTrajectory;
import mobiarmy.war.PathSimulator;
import mobiarmy.war.MapData;
import mobiarmy.war.Player;
import mobiarmy.war.RoomInfo;
import mobiarmy.war.RoomWait;

/**
 *
 * @author Văn Tú
 */
public class Bot extends User {
    
    public boolean remove = false;
    public BotPolicy policyOverride;
    public BotPolicy effectivePolicy() { return policyOverride == null ? BotPolicy.defaults() : policyOverride; }
    public String lastDecision = "Chưa có lượt";
    public long decisions, moves, usedItems, searchNanos, searchCalls, timeouts, skippedTurns;
    public void decision(String reason) { lastDecision = reason; decisions++; }

    // Modified by the game loop; null follows startup configuration.
    public BotSettings.TargetMode targetModeOverride;
    public BotSettings.TargetMode effectiveTargetMode() {
        return targetModeOverride == null ? SETTINGS.targetMode() : targetModeOverride;
    }
    public Object[] invited = null;
    public long waitJoinAnyBoard;
    public long waitInvited;
    public long waitReady;
    public long waitLeave;
    private boolean isLand = true;
    private Player legacyAimTarget;
    private int legacyTargetX, legacyTargetY, legacyOriginX, legacyOriginY;
    boolean legacyAimValid(Player me) {
        return me != null && isTarget(me, legacyAimTarget)
                && java.util.Arrays.asList(me.mapData.players).contains(legacyAimTarget)
                && legacyAimTarget.x == legacyTargetX && legacyAimTarget.y == legacyTargetY
                && me.x == legacyOriginX && me.y == legacyOriginY;
    }

    private BotTurnController turnController;
    static final boolean TACTICAL = Boolean.parseBoolean(System.getenv().getOrDefault("BOT_TACTICAL", "false"));
    static final BotSettings SETTINGS = BotSettings.fromEnvironment(System.getenv());
    private RoomWait observedRoom;
    private boolean observedStarted;
    private MapData observedMap;
    private int observedTurn = -1;
    private final ArrayList<Player> selecteds = new ArrayList<>();

    public Bot(int id, String name) {
        super(id, name);
    }
    
    @Override
    public void update() {
        if (this.remove) {
            this.remove();
            return;
        }
        long now = System.currentTimeMillis();
        observeRoom(now);
        // Each waiting-room visit (including after a match) gets a fresh timeout.
        if (roomWait != null && !roomWait.started && now >= waitLeave) {
            leaveRoomWait();
            observeRoom(now);
        }
        if (now >= waitReady) {
            waitReady = now + SETTINGS.readyDelayMs();
            if (roomWait != null && !roomWait.started) {
                if (roomWait.userID == id) {
                    if (!SETTINGS.requireHuman() || hasConnectedHuman(roomWait)) startGame();
                } else if (!ready) {
                    ready();
                }
            }
        }
        if (effectivePolicy().enabled() || turnController != null && turnController.active()) {
            selecteds.clear();
            if (turnController == null) turnController = new BotTurnController();
            turnController.update(this, now);
        } else {
        //Nếu trong trận và đến lượt
        if (super.roomWait != null && super.roomWait.started && super.roomWait.mapData != null
                && super.index >= 0 && super.index < super.roomWait.mapData.players.length
                && super.roomWait.mapData.getTurn() == super.index) {
            Player me = super.roomWait.mapData.players[super.index];
            if (observedMap != roomWait.mapData || observedTurn != roomWait.mapData.nTurn) {
                selecteds.clear();
                isLand = true;
                observedMap = roomWait.mapData;
                observedTurn = observedMap.nTurn;
                legacyAimTarget = null;
                if (me != null) me.trajectory = null;
            }
            selecteds.removeIf(target -> !isTarget(me, target)
                    || !java.util.Arrays.asList(roomWait.mapData.players).contains(target));
            if (me != null && !me.isDie && !me.isShoot && System.currentTimeMillis() > me.mapData.timeUntilAction2) {
                // The cached solution may outlive its target. Never fire at a corpse or old position.
                if (me.trajectory != null && !legacyAimValid(me)) {
                    me.trajectory = null; legacyAimTarget = null;
                    decision("Hủy góc bắn: mục tiêu chết/rời trận/đổi vị trí");
                    return;
                }
                //Dùng item
                if (!me.isUseItem) {
                    //Kĩ năng đặc biệt
                    if (((float) me.hp / me.hpMax) * 100 < 70 && me.angry >= 100) super.useItem((byte)100);
                    //Dùng hp
                    else if (((float) me.hp / me.hpMax) * 100 < 70 && me.findUnusedItemById(0) != null) super.useItem((byte)0);
                }
                //Bắn
                if (me.trajectory == null) {
                    if (this.selecteds.isEmpty()) {
                        if (me.buocdi == 0) {
                            this.isLand = true;
                        }
                        PathSimulator simulator = new PathSimulator(me.x, me.y, me.x + Util.nextInt(-1, 2), me.y, me.mapData);
                        simulator.simulate();
                        if (simulator.pathFrames.size() > 1) {
                            this.moveLocation(simulator.pathFrames.get(simulator.pathFrames.size() - 1)[0], simulator.pathFrames.get(simulator.pathFrames.size() - 1)[1]);
                        }
                        //Tìm đối thủ
                        for (Player player : super.roomWait.mapData.players) {
                            if (isTarget(me, player)) this.selecteds.add(player);
                        }
                    }
                    if (!this.selecteds.isEmpty()) {
                        Player player = takeTarget(this.selecteds, effectiveTargetMode());
                        legacyAimTarget = player; legacyTargetX = player.x; legacyTargetY = player.y;
                        legacyOriginX = me.x; legacyOriginY = me.y;
                        if (me.glassID == 3 || me.glassID == 2) {
                            me.trajectory = new BulletTrajectory(
                                    super.roomWait.mapData, 
                                    super.index, me.bulletIdByGlassID(),
                                    me.x, me.y,
                                    me.width, me.height,
                                    player.x - player.width / 2, player.y - player.height,
                                    player.width, player.height, 45, 1,
                                    this.isLand, true);
                        } else {
                            me.trajectory = new BulletTrajectory(
                                    super.roomWait.mapData,
                                    super.index, me.bulletIdByGlassID(),
                                    me.x, me.y,
                                    me.width, me.height,
                                    player.x - player.width / 2, player.y - player.height,
                                    player.width, player.height,
                                    me.user.glass().angle, 10,
                                    this.isLand, true);
                        }
                        me.trajectory.start();
                    }
                } else if (me.trajectory.complate) {
                    if (me.trajectory.place) {
                        this.selecteds.clear();
                        super.shoot(me.bulletIdByGlassID(), me.x, me.y, (short)me.trajectory.ang, (byte)me.trajectory.force, (byte)me.trajectory.force2, me.nshoot);
                        me.mapData.isTurn = true;
                    } else {
                        if (me.buocdi < me.theluc) {
                            PathSimulator simulator = new PathSimulator(me.x, me.y, me.trajectory.tX, me.trajectory.tY, me.mapData);
                            simulator.simulate();
                            if (simulator.pathFrames.size() > 1) {
                                this.moveLocation(simulator.pathFrames.get(simulator.pathFrames.size()-1)[0], simulator.pathFrames.get(simulator.pathFrames.size()-1)[1]);
                                if (me.buocdi < me.theluc) {
                                    simulator = new PathSimulator(me.x, me.y, me.x + Util.nextT(-me.theluc, me.theluc), me.y, me.mapData);simulator.simulate();
                                    if (simulator.pathFrames.size() > 1) {
                                        this.moveLocation(simulator.pathFrames.get(simulator.pathFrames.size()-1)[0], simulator.pathFrames.get(simulator.pathFrames.size()-1)[1]);
                                    }
                                }
                            } else {
                                this.isLand = false;
                            }
                        } else {
                            this.isLand = false;
                        }
                        me.trajectory = null;
                    }
                }
            }
        }
        }
        //Mời vào phòng chờ
        if (System.currentTimeMillis() > this.waitInvited) {
            this.waitInvited = System.currentTimeMillis()+ Util.nextInt(3000);
            if (this.invited != null) {
                if (roomWait == null)
                    joinRoomWait((byte)this.invited[0], (byte)this.invited[1], (String) this.invited[2]);
                this.invited = null;
            }
        }
        //Tìm phòng ngẫu nhiên người chơi
        if (System.currentTimeMillis() > this.waitJoinAnyBoard) {
            this.waitJoinAnyBoard = System.currentTimeMillis() + Util.nextInt(3000, 600000);
            if (super.roomWait == null && SETTINGS.autoJoin()) this.joinRandom();
        }
    }
    
    void observeRoom(long now) {
        boolean started = roomWait != null && roomWait.started;
        if (observedRoom != roomWait || observedStarted != started) {
            observedRoom = roomWait;
            observedStarted = started;
            waitReady = now + SETTINGS.readyDelayMs();
            waitLeave = now + SETTINGS.leaveDelayMs();
            selecteds.clear();
            observedMap = null;
            observedTurn = -1;
            isLand = true;
            if (roomWait != null) invited = null;
        }
    }

    static boolean hasConnectedHuman(RoomWait room) {
        for (User player : room.players) {
            if (player != null && !(player instanceof Bot)
                    && player.session != null && player.session.connected) return true;
        }
        return false;
    }

    static boolean isTarget(Player me, Player target) {
        return me != null && target != null && target != me && !target.isDie && target.hp > 0
                && target.countInvisible == 0 && target.countInvisible2 == 0
                && (me.mapData.isFightBoss ? target.isBoss : me.team != target.team);
    }

    static Player takeTarget(ArrayList<Player> targets, BotSettings.TargetMode mode) {
        int index = Util.nextInt(targets.size());
        if (mode == BotSettings.TargetMode.LOW_HP) {
            for (int i = 0; i < targets.size(); i++) {
                if (targets.get(i).hp < targets.get(index).hp) index = i;
            }
        }
        return targets.remove(index);
    }

    private void joinRandom() {
        
        ArrayList<RoomWait> roomWaits = new ArrayList<>();
        for (RoomInfo entry : RoomInfo.entrys) {
            for (RoomWait wait : entry.roomWaits) {
                if (!wait.started && wait.pass.isEmpty() && wait.money <= this.xu && wait.playerLimit > wait.numPlayer && wait.numPlayer <= 7 && wait.type == 0
                        && (!SETTINGS.requireHuman() || hasConnectedHuman(wait))) {
                    roomWaits.add(wait);
                }
            }
        }
        if (!roomWaits.isEmpty()) {
            RoomWait wait = roomWaits.get(Util.nextInt(roomWaits.size()));
            this.joinRoomWait(wait.roomID, wait.boardID, wait.pass);
        }
    }
    
    public void remove() {
        this.remove = true;
        if (turnController != null) turnController.reset();
        super.leaveRoomWait();
        invited = null;
        selecteds.clear();
        observedRoom = null;
        observedMap = null;
        bots.remove(this);
        bot_id.remove(id, this);
        bot_name.remove(name, this);
    }
    
    public static int baseID = Integer.MIN_VALUE;
    
    public static Bot addBot(String name, int glassID, int exp, short[] equipID) {
        Bot bot = new Bot(baseID++, name != null ? name : Util.getRandomCharacters("abcdefghijklmnopqrstuvwxyz0123456789", Util.nextInt(5, 10)));
        bot.luong = Util.nextInt(1000);
        bot.xu = Util.nextInt(1000000);
        bot.getGlass(glassID).isOpen = true;
        bot.selectGlass((byte) glassID);
        bot.glass().addExp(exp, false);
        //Công điểm ngẫu nhiên nếu có
        if (bot.glass().point > 0) {
            int ability[] = new int[5];
            for (int i = 1; i <= bot.glass().point; i++) {
                ability[Util.nextT(0, 1, 0, 3, 1)]++;
            }
            bot.glass().upadtePoint(ability);
        }
        //Mặc trang bị nếu có null sẽ mặc ngẫu nhiên theo level
        if (equipID != null) {
            for (int i = 0; i < equipID.length; i++) {
                Equip equip = Equip.get((byte) glassID, equipID[i]);
                //Bỏ qua nếu không có
                if (equip != null) {
                    equip = equip.deepCopy();
                    equip.renewalDate = System.currentTimeMillis();
                    equip.isUse = true;
                    bot.addEquip(equip);
                }
            }
        } else {
            //Lấy ngẫu nhiên trong trang bị cửa hàng để mặc vào
            ArrayList<Equip> equips = ShopEquipment.generate((byte) glassID);
            ArrayList<Equip>[] es = new ArrayList[]{new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>()};
            for (Equip equip : equips) {
                if (equip.level <= bot.glass().level && equip.vip == 0) {
                    es[equip.type].add(equip);
                }
            }
            for (ArrayList<Equip> esList : es) {
                if (!esList.isEmpty()) {
                    Equip equip = esList.get(Util.nextInt(esList.size()));
                    equip = equip.deepCopy();
                    equip.renewalDate = System.currentTimeMillis();
                    equip.isUse = true;
                    bot.addEquip(equip);
                }
            }
        }
        //Thêm ngọc để chuẩn bị ép vào trang bị
        for (int i = 0; i < 100; i++) {
            bot.addLinhTinh(Util.nextT(10, 40, 10, 30, 40) + Util.nextInt(7, 9), 1);
        }
        for (Equip equip : bot.equips) {
            // Bound attempts: a failed combine must not stall the game loop/admin command.
            for (int attempts = 0; equip.slot() > 0 && attempts < 32; attempts++) {
                int slotsBefore = equip.slot();
                kt: {
                    for (LinhTinh linhtinh : bot.linhtinhs) {
                        if (linhtinh.id < 50) {
                            bot.getSelect().reset();
                            bot.getSelect().addElement(0, equip.dbKey, 1);
                            bot.getSelect().addElement(1, linhtinh.id, 1);
                            bot.getSelect().make();
                            bot.getConfirm().confirm();
                            break kt;
                        }
                    }
                    break;
                }
                if (equip.slot() >= slotsBefore) break;
            }
        }
        bot.glass().updateAll();
        add(bot);
        return bot;
    }

    // Tìm Bot theo ID
    public static Bot findById(int id) {
        return bot_id.get(id);
    }
    
    public static void add(Bot bot) {
        if (bot.remove || bot_id.containsKey(bot.id))
            throw new IllegalArgumentException("Bot đã bị xóa hoặc trùng ID: " + bot.id);
        bots.add(bot);
        bot_id.put(bot.id, bot);
        bot_name.put(bot.name, bot);
    }
    
    public static ArrayList<Bot> bots = new ArrayList<>();
    public static HashMap<Integer, Bot> bot_id = new HashMap<>();
    public static HashMap<String, Bot> bot_name = new HashMap<>();
    
    private static int updateOffset;
    public static void updateBot() {
        BotTurnController.beginTick();
        Bot[] snapshot = bots.toArray(new Bot[0]);
        if (snapshot.length == 0) return;
        int start = Math.floorMod(updateOffset++, snapshot.length);
        for (int i = 0; i < snapshot.length; i++) {
            Bot bot = snapshot[(start + i) % snapshot.length];
            boolean exhausted = BotTurnController.budgetExhausted();
            if (bot.remove || !bot.lock) bot.update();
            else if (bot.turnController != null) bot.turnController.reset();
            if (!exhausted && BotTurnController.budgetExhausted()) updateOffset = (start + i + 1) % snapshot.length;
        }
    }

    public static void generateBot() {
        for (int i = bots.size(); i < SETTINGS.count(); i++) {
            Bot.addBot(null, Util.nextInt(10), Util.nextInt(50000000), null);
//            Bot.addBot(null, Util.nextInt(10), Util.nextInt(100), null);
        }
    }

}
