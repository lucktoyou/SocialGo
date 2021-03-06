package com.socialgo.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.SparseArray
import com.socialgo.core.adapter.IJsonAdapter
import com.socialgo.core.adapter.IRequestAdapter
import com.socialgo.core.adapter.impl.DefaultRequestAdapter
import com.socialgo.core.common.SocialConstants
import com.socialgo.core.common.SocialError
import com.socialgo.core.listener.FunctionListener
import com.socialgo.core.listener.OnLoginListener
import com.socialgo.core.listener.OnPayListener
import com.socialgo.core.listener.OnShareListener
import com.socialgo.core.model.ShareEntity
import com.socialgo.core.model.ShareEntityChecker
import com.socialgo.core.model.token.BaseAccessToken
import com.socialgo.core.platform.IPlatform
import com.socialgo.core.platform.PlatformCreator
import com.socialgo.core.platform.Target
import com.socialgo.core.utils.SocialGoUtils
import com.socialgo.core.utils.SocialLogUtils
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


/**
 * 第三方登录、分享、支付组件入口（支持QQ、微信、微博、支付宝）。
 * 使用前初始化（配置平台信息、注册平台）。
 */
object SocialGo {

    private var mSocialSdkConfig: SocialGoConfig? = null
    private var mJsonAdapter: IJsonAdapter? = null
    private var mRequestAdapter: IRequestAdapter? = null

    private var mPlatformCreatorMap = SparseArray<PlatformCreator>()
    private var mExecutorService: ExecutorService? = null
    private var mPlatform: IPlatform? = null
    private var mHandler: Handler = Handler(Looper.getMainLooper())

    private const val INVALID_PARAM = -1
    private const val ACTION_TYPE_LOGIN = 0
    private const val ACTION_TYPE_SHARE = 1
    private const val ACTION_TYPE_PAY = 2

    private const val KEY_SHARE_MEDIA_OBJ = "KEY_SHARE_MEDIA_OBJ"  // media obj key
    private const val KEY_PAY_PARAMS = "KEY_PAY_PARAMS"            // pay params
    private const val KEY_SHARE_TARGET = "KEY_SHARE_TARGET"        // share target
    private const val KEY_LOGIN_TARGET = "KEY_LOGIN_TARGET"        // login target
    const val KEY_ACTION_TYPE = "KEY_ACTION_TYPE"                  // action type


    ///////////////////////////////////////////////////////////////////////////
    // 初始化配置
    ///////////////////////////////////////////////////////////////////////////
    fun init(config: SocialGoConfig): SocialGo {
        mSocialSdkConfig = config
        return this
    }


    ///////////////////////////////////////////////////////////////////////////
    // Platform 注册
    ///////////////////////////////////////////////////////////////////////////
    fun registerPlatform(vararg creators: PlatformCreator): SocialGo {
        mPlatformCreatorMap.clear()
        for (creator in creators) {
            val platform = when (creator.javaClass.name) {
                SocialConstants.QQ_CREATOR -> Target.PLATFORM_QQ
                SocialConstants.WX_CREATOR -> Target.PLATFORM_WX
                SocialConstants.WB_CREATOR -> Target.PLATFORM_WB
                SocialConstants.ALI_CREATOR -> Target.PLATFORM_ALI
                else -> -1
            }
            mPlatformCreatorMap.put(platform, creator)
        }
        return this
    }

    fun registerQQPlatform(creator: PlatformCreator): SocialGo {
        mPlatformCreatorMap.put(Target.PLATFORM_QQ, creator)
        return this
    }

    fun registerWxPlatform(creator: PlatformCreator): SocialGo {
        mPlatformCreatorMap.put(Target.PLATFORM_WX, creator)
        return this
    }

    fun registerWbPlatform(creator: PlatformCreator): SocialGo {
        mPlatformCreatorMap.put(Target.PLATFORM_WB, creator)
        return this
    }

    fun registerAliPlatform(creator: PlatformCreator): SocialGo {
        mPlatformCreatorMap.put(Target.PLATFORM_ALI, creator)
        return this
    }

    ///////////////////////////////////////////////////////////////////////////
    // 登录SDK
    ///////////////////////////////////////////////////////////////////////////
    private var mLoginListener: OnLoginListener? = null

    /**
     * 开始登陆
     */
    fun doLogin(context: Context?, @Target.LoginTarget loginTarget: Int, listener: OnLoginListener.() -> Unit) {
        if (context == null) {
            SocialLogUtils.e("context not be null...")
        } else {
            val function = FunctionListener()
            val callback = object : OnLoginListener {
                override fun getFunction() = function
            }
            callback.listener()
            mLoginListener = callback
            function.onStart?.invoke()
            val platform = makePlatform(context, loginTarget)
            if (!platform.isInstall(context)) {
                function.onFailure?.invoke(SocialError(SocialError.CODE_NOT_INSTALL))
                return
            }
            val intent = Intent(context, platform.getActionClazz())
            intent.putExtra(KEY_ACTION_TYPE, ACTION_TYPE_LOGIN)
            intent.putExtra(KEY_LOGIN_TARGET, loginTarget)
            context.startActivity(intent)
            if (context is Activity) {
                context.overridePendingTransition(0, 0)
            }
        }
    }

    /**
     * 跳转到中间页后，激活登录操作
     */
    private fun activeLogin(activity: Activity) {
        val intent = activity.intent
        val actionType = intent?.getIntExtra(KEY_ACTION_TYPE, INVALID_PARAM)
        val loginTarget = intent?.getIntExtra(KEY_LOGIN_TARGET, INVALID_PARAM)
        if (actionType == INVALID_PARAM) {
            SocialLogUtils.e("activeLogin actionType无效")
            return
        }
        if (actionType != ACTION_TYPE_LOGIN) {
            return
        }
        if (loginTarget == INVALID_PARAM) {
            SocialLogUtils.e("loginTargetType无效")
            return
        }
        getPlatform()?.login(activity, getLoginFinishListener(activity))
    }

    /**
     * 获取登录结束关闭中间页的监听
     */
    private fun getLoginFinishListener(activity: Activity): OnLoginListener {
        val function = FunctionListener()
        val listener = object : OnLoginListener {
            override fun getFunction() = function
        }
        listener.onStart {
            mLoginListener?.getFunction()?.onStart?.invoke()
        }
        listener.onSuccess {
            mLoginListener?.getFunction()?.onLoginSuccess?.invoke(it)
            finish(activity)
        }
        listener.onFailure {
            mLoginListener?.getFunction()?.onFailure?.invoke(it)
            finish(activity)
        }
        listener.onCancel {
            mLoginListener?.getFunction()?.onCancel?.invoke()
            finish(activity)
        }
        return listener
    }


    ///////////////////////////////////////////////////////////////////////////
    // 分享SDK
    ///////////////////////////////////////////////////////////////////////////
    private var mShareListener: OnShareListener? = null

    /**
     * 开始分享，供外面调用
     * @param context         上下文
     * @param shareTarget     分享目标
     * @param entity        分享对象
     * @param listener 分享监听
     */
    fun doShare(context: Context?, @Target.ShareTarget shareTarget: Int, entity: ShareEntity, listener: OnShareListener.() -> Unit) {
        if(context == null){
            SocialLogUtils.e("context not be null...")
        }else {
            val function = FunctionListener()
            val callback = object : OnShareListener {
                override fun getFunction() = function
            }
            callback.listener()
            mShareListener = callback
            function.onShareStart?.invoke(shareTarget, entity)
            getExecutor().execute {
                if (entity.prepareImageInBackground(context)) {
                    getHandler().post {
                        startShare(context, shareTarget, entity, callback)
                    }
                }
            }
        }
    }

    /**
     *  开始分享
     */
    private fun startShare(context: Context, @Target.ShareTarget shareTarget: Int, entity: ShareEntity, listener: OnShareListener) {
        // 对象是否完整
        if (!ShareEntityChecker.checkShareValid(entity)) {
            listener.getFunction().onFailure?.invoke(SocialError(SocialError.CODE_SHARE_ENTITY_INVALID).append(ShareEntityChecker.getErrorMsg()))
            return
        }
        val platform = makePlatform(context, shareTarget)
        if (!platform.isInstall(context)) {
            listener.getFunction().onFailure?.invoke(SocialError(SocialError.CODE_NOT_INSTALL))
            return
        }
        val intent = Intent(context, platform.getActionClazz())
        intent.putExtra(KEY_ACTION_TYPE, ACTION_TYPE_SHARE)
        intent.putExtra(KEY_SHARE_MEDIA_OBJ, entity)
        intent.putExtra(KEY_SHARE_TARGET, shareTarget)
        context.startActivity(intent)
        if (context is Activity) {
            context.overridePendingTransition(0, 0)
        }
    }

    /**
     * 激活分享
     */
    private fun activeShare(activity: Activity) {
        val intent = activity.intent
        val actionType = intent?.getIntExtra(KEY_ACTION_TYPE, INVALID_PARAM)
        val shareTarget = intent?.getIntExtra(KEY_SHARE_TARGET, INVALID_PARAM)
        val entity = intent?.getParcelableExtra<ShareEntity>(KEY_SHARE_MEDIA_OBJ)
        if (actionType != ACTION_TYPE_SHARE)
            return
        if (shareTarget == INVALID_PARAM) {
            SocialLogUtils.e("shareTargetType无效")
            return
        }
        if (entity == null) {
            SocialLogUtils.e("shareObj == null")
            return
        }
        if (mShareListener == null) {
            SocialLogUtils.e("请设置 OnShareListener")
            return
        }
        getPlatform()?.share(activity, shareTarget ?: 0, entity, getShareFinishListener(activity))
    }

    /**
     * 获取分享中间页的监听
     */
    private fun getShareFinishListener(activity: Activity): OnShareListener {
        val function = FunctionListener()
        val listener = object : OnShareListener {
            override fun getFunction() = function
        }
        listener.onStart { shareTarget, obj ->
            mShareListener?.getFunction()?.onShareStart?.invoke(shareTarget, obj)
        }
        listener.onSuccess {
            mShareListener?.getFunction()?.onSuccess?.invoke()
            finish(activity)
        }
        listener.onFailure {
            mShareListener?.getFunction()?.onFailure?.invoke(it)
            finish(activity)
        }
        listener.onCancel {
            mShareListener?.getFunction()?.onCancel?.invoke()
            finish(activity)
        }
        return listener
    }

    ///////////////////////////////////////////////////////////////////////////
    // 支付SDK
    ///////////////////////////////////////////////////////////////////////////
    private var mPayListener: OnPayListener? = null

    /**
     * 支付SDK入口
     */
    fun doPay(context: Context?, params: String, @Target.PayTarget payTarget: Int, listener: OnPayListener.() -> Unit) {
        if(context == null){
            SocialLogUtils.e("context not be null...")
        }else {
            val function = FunctionListener()
            val callback = object : OnPayListener {
                override fun getFunction() = function
            }
            callback.listener()
            mPayListener = callback
            function.onStart?.invoke()
            val platform = makePlatform(context, payTarget)
            if (!platform.isInstall(context)) {
                function.onFailure?.invoke(SocialError(SocialError.CODE_NOT_INSTALL))
                return
            }
            val intent = Intent(context, platform.getActionClazz())
            intent.putExtra(KEY_ACTION_TYPE, ACTION_TYPE_PAY)
            intent.putExtra(KEY_PAY_PARAMS, params)
            context.startActivity(intent)
            if (context is Activity) {
                context.overridePendingTransition(0, 0)
            }
        }
    }

    /**
     * 激活支付
     */
    private fun activePay(activity: Activity) {
        val intent = activity.intent
        val actionType = intent.getIntExtra(KEY_ACTION_TYPE, INVALID_PARAM)
        val payParams = intent.getStringExtra(KEY_PAY_PARAMS)
        if (actionType != ACTION_TYPE_PAY)
            return
        if (mPayListener == null) {
            SocialLogUtils.e("请设置 OnPayListener")
            return
        }
        getPlatform()?.doPay(activity, payParams!!, getPayFinishListener(activity))
    }

    /**
     * 获取支付中间页的结束监听，用于关闭中间页
     */
    private fun getPayFinishListener(activity: Activity): OnPayListener {
        val function = FunctionListener()
        val listener = object : OnPayListener {
            override fun getFunction() = function
        }
        listener.onStart {
            mPayListener?.getFunction()?.onStart?.invoke()
        }
        listener.onSuccess {
            mPayListener?.getFunction()?.onSuccess?.invoke()
            finish(activity)
        }
        listener.onFailure {
            mPayListener?.getFunction()?.onFailure?.invoke(it)
            finish(activity)
        }
        listener.onCancel {
            mPayListener?.getFunction()?.onCancel?.invoke()
            finish(activity)
        }
        listener.onDealing {
            mPayListener?.getFunction()?.onDealing?.invoke()
            finish(activity)
        }
        return listener
    }


    fun activeAction(activity: Activity, actionType: Int) {
        if (actionType != -1) {
            when (actionType) {
                ACTION_TYPE_LOGIN -> activeLogin(activity)
                ACTION_TYPE_SHARE -> activeShare(activity)
                ACTION_TYPE_PAY -> activePay(activity)
            }
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // Platform相关操作
    ///////////////////////////////////////////////////////////////////////////

    fun getPlatform(): IPlatform? {
        return mPlatform
    }

    private fun makePlatform(context: Context, target: Int): IPlatform {
        val platformTarget = Target.mapPlatform(target)
        val platform = getPlatform(context, platformTarget)
                ?: throw IllegalArgumentException(Target.toDesc(target) + "  创建platform失败，请检查参数 " + getConfig().toString())
        mPlatform = platform
        return platform
    }

    private fun getPlatform(context: Context, target: Int): IPlatform? {
        val creator = mPlatformCreatorMap.get(target)
        return creator?.create(context, target)
    }

    ///////////////////////////////////////////////////////////////////////////
    // JsonAdapter
    ///////////////////////////////////////////////////////////////////////////
    fun setJsonAdapter(jsonAdapter: IJsonAdapter): SocialGo {
        mJsonAdapter = jsonAdapter
        return this
    }

    fun getJsonAdapter(): IJsonAdapter {
        if (mJsonAdapter == null) {
            throw IllegalStateException("为了不引入其他的json解析依赖，特地将这部分放出去，必须添加一个对应的 json 解析工具，参考代码 GsonJsonAdapter.java")
        }
        return mJsonAdapter!!
    }

    ///////////////////////////////////////////////////////////////////////////
    // RequestAdapter
    ///////////////////////////////////////////////////////////////////////////
    fun setRequestAdapter(requestAdapter: IRequestAdapter): SocialGo {
        mRequestAdapter = requestAdapter
        return this
    }

    fun getRequestAdapter(): IRequestAdapter {
        return if (mRequestAdapter != null) {
            mRequestAdapter!!
        } else DefaultRequestAdapter()
    }

    /**
     * 获取Handler
     */
    fun getHandler(): Handler {
        return mHandler
    }

    /**
     * 获取线程池
     */
    fun getExecutor(): ExecutorService {
        if (mExecutorService == null) {
            mExecutorService = Executors.newSingleThreadExecutor()
        }
        return mExecutorService!!
    }

    /**
     * 获取配置
     */
    fun getConfig(): SocialGoConfig {
        if (mSocialSdkConfig == null) {
            throw IllegalStateException("invoke SocialGo.init() first please")
        }
        return mSocialSdkConfig!!
    }


    /**
     * 发送短信分享
     * @param context 上下文
     * @param phone   手机号
     * @param msg     内容
     */
    fun sendSms(context: Context, phone: String, msg: String) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = Uri.parse("smsto:$phone")
        intent.putExtra("sms_body", msg)
        intent.type = "vnd.android-dir/mms-sms"
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }

    /**
     * 发送邮件分享
     * @param context 上下文
     * @param mailto  email
     * @param subject 主题
     * @param msg     内容
     */
    fun sendEmail(context: Context, mailto: String, subject: String, msg: String) {
        val intent = Intent(Intent.ACTION_SENDTO)
        intent.data = Uri.parse("mailto:$mailto")
        intent.putExtra(Intent.EXTRA_SUBJECT, subject)
        intent.putExtra(Intent.EXTRA_TEXT, msg)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }

    /**
     * 打开某个平台app
     */
    fun openApp(context: Context,@Target.PlatformTarget target: Int): Boolean {
        val platform = Target.mapPlatform(target)
        var pkgName: String? = null
        when (platform) {
            Target.PLATFORM_QQ-> pkgName = SocialConstants.QQ_PKG
            Target.PLATFORM_WX -> pkgName = SocialConstants.WX_PKG
            Target.PLATFORM_WB -> pkgName = SocialConstants.WB_PKG
            Target.PLATFORM_ALI-> pkgName = SocialConstants.ALI_PKG
            else-> pkgName == null
        }
        return !TextUtils.isEmpty(pkgName) && SocialGoUtils.openApp(context, pkgName)
    }

    /**
     * 清除所有的本地Token
     */
    fun clearAllToken(context: Context?) {
        BaseAccessToken.clearToken(context, Target.LOGIN_QQ)
        BaseAccessToken.clearToken(context, Target.LOGIN_WX)
        BaseAccessToken.clearToken(context, Target.LOGIN_WB)
    }

    /**
     * 清除指定平台的Token
     */
    fun clearToken(context: Context?, @Target.LoginTarget loginTarget: Int) {
        BaseAccessToken.clearToken(context, loginTarget)
    }

    /**
     * 操作结束，关闭中间页，销毁静态变量
     */
    fun release(activity: Activity) {
        mPlatform?.recycle()
        mPlatform = null
        if (!activity.isFinishing) {
            activity.finish()
        }
    }

    private fun finish(activity: Activity) {
        release(activity)
        mLoginListener = null
        mPayListener = null
        mShareListener = null
    }
}
