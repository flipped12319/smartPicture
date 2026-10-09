package com.flipped.picturebackend.manager.ws;

import cn.hutool.core.util.StrUtil;
import com.flipped.picturebackend.model.vo.PictureEditParticipantVO;
import lombok.Getter;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 编辑房间：一张图片对应一个房间，房间内同时只允许一个人在编辑。
 * <p>
 * 线程安全说明：
 * <ul>
 *     <li>参与者集合使用 {@link ConcurrentHashMap}；</li>
 *     <li>「当前编辑者」用 {@link AtomicReference} + CAS 抢占，无锁地保证同一时刻只有一人能编辑。</li>
 * </ul>
 * 状态变更都在 Disruptor 的消费者线程上串行发生，使用并发容器主要是为了让
 * WebSocket 回调线程（连接建立/断开）也能安全读取。
 */
public class EditRoom {

    /**
     * 被协同编辑的图片 id
     */
    @Getter
    private final Long pictureId;

    /**
     * 参与者：sessionId -> 连接包装
     */
    private final Map<String, EditSession> sessions = new ConcurrentHashMap<>();

    /**
     * 当前正在编辑的会话 id；为空表示没有人在编辑
     */
    private final AtomicReference<String> editorSessionId = new AtomicReference<>(null);

    /**
     * 本轮编辑会话内的操作记录，用于让后加入的人把画布追平到当前状态。
     * <p>
     * 只在消费者线程上读写，因此用普通 List 即可；对外只提供快照拷贝。
     */
    private final List<String> operationHistory = new ArrayList<>();

    /**
     * 操作记录条数上限，避免一次超长编辑会话把内存撑大
     */
    private static final int MAX_OPERATION_HISTORY = 500;

    public EditRoom(Long pictureId) {
        this.pictureId = pictureId;
    }

    /**
     * 记下一次编辑操作
     */
    public void recordOperation(String payload) {
        if (StrUtil.isBlank(payload)) {
            return;
        }
        operationHistory.add(payload);
        // 超出上限就丢掉最早的：后续操作会继续覆盖画布状态，影响可控
        while (operationHistory.size() > MAX_OPERATION_HISTORY) {
            operationHistory.remove(0);
        }
    }

    /**
     * 清空操作记录（新一轮编辑开始时调用）
     */
    public void clearOperations() {
        operationHistory.clear();
    }

    /**
     * 操作记录快照（拷贝一份，便于安全序列化）
     */
    public List<String> snapshotOperations() {
        return new ArrayList<>(operationHistory);
    }

    /**
     * 加入房间；已存在则不重复加入
     */
    public void join(String sessionId, WebSocketSession session, PictureEditParticipantVO participant) {
        sessions.putIfAbsent(sessionId, new EditSession(sessionId, session, participant));
    }

    /**
     * 离开房间；如果离开的人正在编辑，一并释放编辑权
     *
     * @return 被移除的连接；不存在时返回 null
     */
    public EditSession leave(String sessionId) {
        EditSession removed = sessions.remove(sessionId);
        editorSessionId.compareAndSet(sessionId, null);
        return removed;
    }

    public EditSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    public boolean isEmpty() {
        return sessions.isEmpty();
    }

    /**
     * 抢占编辑权（CAS 无锁实现）
     *
     * @return true 表示抢到或者本来就是自己；false 表示已被别人占用
     */
    public boolean tryAcquireEditing(String sessionId) {
        if (sessionId.equals(editorSessionId.get())) {
            return true;
        }
        return editorSessionId.compareAndSet(null, sessionId);
    }

    /**
     * 释放编辑权，只有当前编辑者才能释放成功
     */
    public boolean releaseEditing(String sessionId) {
        return editorSessionId.compareAndSet(sessionId, null);
    }

    /**
     * 当前编辑者所在的连接；没有人在编辑时返回 null
     */
    public EditSession getEditorSession() {
        String sessionId = editorSessionId.get();
        return sessionId == null ? null : sessions.get(sessionId);
    }

    /**
     * 当前编辑者的用户 id；没有人在编辑时返回 null
     */
    public Long getEditorUserId() {
        EditSession editor = getEditorSession();
        return editor == null ? null : editor.getParticipant().getUserId();
    }

    /**
     * 刷新每个参与者的 editing 标记，保证广播出去的状态和当前编辑者一致
     */
    public void refreshEditingFlag() {
        String editorId = editorSessionId.get();
        sessions.forEach((sessionId, editSession) ->
                editSession.getParticipant().setEditing(sessionId.equals(editorId)));
    }

    /**
     * 参与者列表（已拷贝，可安全遍历）
     */
    public List<PictureEditParticipantVO> listParticipants() {
        List<PictureEditParticipantVO> participants = new ArrayList<>(sessions.size());
        sessions.values().forEach(editSession -> participants.add(editSession.getParticipant()));
        return participants;
    }

    /**
     * 连接快照（已拷贝，可安全遍历）
     */
    public List<EditSession> listSessions() {
        return new ArrayList<>(sessions.values());
    }

    /**
     * 房间内的一个连接：WebSocket 会话 + 参与者的展示信息
     */
    @Getter
    public static class EditSession {

        private final String sessionId;

        private final WebSocketSession session;

        private final PictureEditParticipantVO participant;

        public EditSession(String sessionId, WebSocketSession session, PictureEditParticipantVO participant) {
            this.sessionId = sessionId;
            this.session = session;
            this.participant = participant;
        }
    }
}
