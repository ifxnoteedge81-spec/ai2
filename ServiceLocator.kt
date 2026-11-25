package com.personal.aicomputer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.personal.aicomputer.accessibility.HandsController
import com.personal.aicomputer.agent.AgentController
import com.personal.aicomputer.agent.AgentEngine
import com.personal.aicomputer.agent.ApprovalCenter
import com.personal.aicomputer.agent.MemoryStore
import com.personal.aicomputer.agent.TaskRepository
import com.personal.aicomputer.agent.ToolRegistry
import com.personal.aicomputer.ai.AiGateway
import com.personal.aicomputer.ai.GeminiProvider
import com.personal.aicomputer.ai.HttpSupport
import com.personal.aicomputer.ai.OpenRouterProvider
import com.personal.aicomputer.browser.BrowserController
import com.personal.aicomputer.resources.ResourceMonitor
import com.personal.aicomputer.resources.ResourcePolicy
import com.personal.aicomputer.runtime.RuntimeManager
import com.personal.aicomputer.runtime.ShellExecutor
import com.personal.aicomputer.runtime.Workspace
import com.personal.aicomputer.security.AppLog
import com.personal.aicomputer.security.SecureKeyStore
import com.personal.aicomputer.storage.JsonFileStore
import com.personal.aicomputer.storage.SettingsStore
import com.personal.aicomputer.tools.BrowserTools
import com.personal.aicomputer.tools.DeviceTools
import com.personal.aicomputer.tools.FileTools
import com.personal.aicomputer.tools.MemoryTools
import com.personal.aicomputer.tools.ShellTools
import com.personal.aicomputer.tools.SystemTools
import com.personal.aicomputer.tools.WebClient
import com.personal.aicomputer.tools.WebTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Manual dependency wiring (no DI framework, keeps the build small). */
object ServiceLocator {
    lateinit var appContext: Context; private set
    lateinit var settings: SettingsStore; private set
    lateinit var keys: SecureKeyStore; private set
    lateinit var log: AppLog; private set
    lateinit var workspace: Workspace; private set
    lateinit var monitor: ResourceMonitor; private set
    lateinit var policy: ResourcePolicy; private set
    lateinit var runtime: RuntimeManager; private set
    lateinit var shell: ShellExecutor; private set
    lateinit var browser: BrowserController; private set
    lateinit var hands: HandsController; private set
    lateinit var tools: ToolRegistry; private set
    lateinit var gateway: AiGateway; private set
    lateinit var memory: MemoryStore; private set
    lateinit var tasks: TaskRepository; private set
    lateinit var approvals: ApprovalCenter; private set
    lateinit var engine: AgentEngine; private set
    lateinit var controller: AgentController; private set
    lateinit var gemini: GeminiProvider; private set
    lateinit var openRouter: OpenRouterProvider; private set
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun init(app: android.app.Application) {
        val ctx = app.applicationContext
        appContext = ctx
        settings = SettingsStore(ctx)
        keys = SecureKeyStore(ctx)
        log = AppLog()
        workspace = Workspace(File(ctx.filesDir, "pac"))
        monitor = ResourceMonitor(ctx, workspace)
        policy = ResourcePolicy(settings, monitor)
        runtime = RuntimeManager(ctx, workspace, settings, log, HttpSupport.client)
        runtime.refresh()
        shell = ShellExecutor(runtime, workspace, policy)
        browser = BrowserController(ctx)
        hands = HandsController(ctx)
        memory = MemoryStore(JsonFileStore(File(workspace.memoryDir, "memory.json")))
        tasks = TaskRepository(JsonFileStore(File(workspace.memoryDir, "tasks.json")))
        approvals = ApprovalCenter()

        gemini = GeminiProvider(HttpSupport.client, keys)
        openRouter = OpenRouterProvider(HttpSupport.client, keys)
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        gateway = AiGateway(
            providers = mapOf(gemini.id to gemini, openRouter.id to openRouter),
            settings = settings,
            keys = keys,
            log = log,
            contextBudgetChars = { policy.contextChars() },
            isOnline = {
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            },
        )

        val web = WebClient()
        tools = ToolRegistry().apply {
            registerAll(FileTools(workspace, policy).all())
            registerAll(ShellTools(shell).all())
            registerAll(WebTools(web, workspace, policy).all())
            registerAll(BrowserTools(browser, policy).all())
            registerAll(DeviceTools(hands).all())
            registerAll(MemoryTools(memory).all())
            registerAll(SystemTools(runtime, monitor, policy).all())
        }

        engine = AgentEngine(
            gateway, tools, tasks, memory, approvals, policy, settings, log,
            environment = {
                "Runtime: ${runtime.activeMode().label} (workspace is the shell's working directory). " +
                    "Android device control: ${if (hands.isEnabled()) "ENABLED" else "disabled (user must enable Accessibility)"}. " +
                    "Browser: in-app WebView with JavaScript. Offline AI is not available."
            },
        )
        controller = AgentController(ctx, engine, tasks, appScope)
    }
}
