package com.ananoesis.shell.ai;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import com.ananoesis.shell.config.SshProperties;
import com.ananoesis.shell.entity.Host;
import com.ananoesis.shell.mapper.HostMapper;
import com.ananoesis.shell.ssh.SshAuthMethod;
import com.ananoesis.shell.ssh.SshConnectionService;
import com.ananoesis.shell.ssh.SshExecService;
import com.ananoesis.shell.ssh.SshTarget;
import com.ananoesis.shell.support.FakeSshServer;
import com.ananoesis.shell.support.SshTestDoubles.FixedTargetResolver;

/**
 * 智能体测试的装配工具（tasks 9.x 测试共用）。
 *
 * <h2>WHY 要自己装配，而不是直接用容器里的 {@code AgentTools} / {@code ApprovedCommandRunner}</h2>
 * <p>容器里那两个 bean 依赖的是<b>真实的</b> {@code SshExecService}，它会拿
 * {@code hosts} 表里的地址与 {@code credentials} 表里的密文去连真机器。
 * 测试要的是"命令确实经 6.5 的 exec 通道落到了远端"这条端到端事实，
 * 于是把解析器换成 {@link FixedTargetResolver}、目标换成 {@link FakeSshServer}，
 * 其余（{@code SettingsService} 的阈值、{@code ApprovalAuditService} 的审计落库）
 * 仍用容器里的真 bean——被测的分级逻辑与审计写入因此没有被替身掉。</p>
 *
 * <h2>WHY 两个测试类各自启动一个 {@link FakeSshServer} 而不共享</h2>
 * <p>JUnit 5 的 {@code @BeforeAll} 按<b>测试类</b>各跑一次。若把服务器放在共用基类的静态字段上，
 * 第二个类会把第一个类的实例覆盖掉，前者的端口就泄漏了。MINA 会各自挑一个空闲端口，
 * 两个实例互不干扰，代价是一秒左右的启动时间。</p>
 */
final class AgentTestWiring {

    private AgentTestWiring() {
    }

    /**
     * @return "快失败"的 SSH 参数
     *
     * <p>WHY 不用容器里的 {@code SshProperties} bean：它的 {@code connectTimeout} 是 15 秒，
     * 一次连不上就要等满，测试会慢到没人愿意跑。</p>
     */
    static SshProperties fastSshProperties() {
        SshProperties properties = new SshProperties();
        properties.setConnectTimeout(Duration.ofSeconds(3));
        properties.setExecTimeout(Duration.ofSeconds(5));
        properties.setExecMaxOutputBytes(65_536);
        properties.setTerminalIdleTimeout(Duration.ofMinutes(5));
        properties.setPtyTerm("xterm-256color");
        properties.setPtyColumns(80);
        properties.setPtyRows(24);
        return properties;
    }

    /** @return 指向内嵌 SSH 服务器的目标（密码认证） */
    static SshTarget targetOn(FakeSshServer fake) {
        return SshTarget.of("127.0.0.1", fake.port(), FakeSshServer.USERNAME,
                SshAuthMethod.password(FakeSshServer.PASSWORD));
    }

    /** @return 一个"无论问哪台主机都连到内嵌服务器"的 exec 服务 */
    static SshExecService execServiceOn(FakeSshServer fake) {
        SshProperties properties = fastSshProperties();
        return new SshExecService(new SshConnectionService(properties), properties,
                new FixedTargetResolver(targetOn(fake)));
    }

    /**
     * 往 {@code hosts} 表插一行，返回它的 id。
     *
     * <p>WHY 必须插真行：{@code application.yml} 打开了 {@code foreign_keys=on}，
     * {@code ai_conversations.host_id} 引用 {@code hosts(id)}，凭空造一个 UUID 会直接违反外键。
     * 而 {@code ConversationService#create} 也会先校验主机存在。</p>
     *
     * <p>WHY 显式 setId：实体的主键策略是 {@code ASSIGN_UUID}，MyBatis-Plus 会生成
     * <b>无连字符</b>的 32 位串；而全链路其余部分用的是 {@code UUID#toString()}（带连字符），
     * 两者对不上，会话就再也查不到自己的主机。</p>
     */
    static UUID insertHost(HostMapper hosts, String name) {
        Host row = new Host();
        UUID id = UUID.randomUUID();
        row.setId(id.toString());
        row.setName(name);
        row.setHost("127.0.0.1");
        row.setPort(22);
        row.setUsername("ops");
        row.setAuthType("password");
        hosts.insert(row);
        return id;
    }

    /** @return 在调用线程上直接跑任务的执行器 */
    static DirectExecutorService directExecutor() {
        return new DirectExecutorService();
    }

    /**
     * 同步执行器：{@code execute} 就地跑完，不建线程。
     *
     * <p>WHY 需要它：{@code AiAgentService#runTurn} 是包级同步方法，多数用例直接调它，
     * 执行器压根用不上；但 {@code submit} 的"在飞回合互斥"必须经过执行器才测得到，
     * 而构造器要求一个非 null 的 {@code ExecutorService}。用真实的线程池会让
     * "提交 → 断言帧序列"变成竞态，测试就得处处加等待。</p>
     */
    static final class DirectExecutorService extends AbstractExecutorService {

        private volatile boolean shutdown;

        @Override
        public void execute(Runnable command) {
            if (shutdown) {
                throw new java.util.concurrent.RejectedExecutionException("执行器已关闭");
            }
            command.run();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }
}
