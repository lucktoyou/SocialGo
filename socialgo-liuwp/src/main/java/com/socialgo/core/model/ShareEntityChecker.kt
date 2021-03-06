package com.socialgo.core.model


import com.socialgo.core.utils.SocialGoUtils
import java.lang.ref.WeakReference

/**
 * 检查分享对象的工具类
 */
object ShareEntityChecker {

    private var sErrMsgRef = ErrMsgRef("", null)

    class ErrMsgRef(msg: String, obj: ShareEntity?) : WeakReference<String>("$msg;${obj?.toString()}.")

    fun getErrorMsg(): String? {
        return sErrMsgRef.get()
    }

    fun checkShareValid(entity: ShareEntity): Boolean {
        return when (entity.getEntityType()) {
            ShareEntity.ENTITY_IS_TEXT -> {
                isSummaryValid(entity)
            }
            ShareEntity.ENTITY_IS_IMAGE -> {
                isLocalImageValid(entity)
            }
            ShareEntity.ENTITY_IS_WEB -> {
                (isUrlValid(entity) && isTitleSummaryValid(entity) && isLocalImageValid(entity))
            }
            else -> false
        }
    }

    private fun isTitleSummaryValid(entity: ShareEntity): Boolean {
        val valid = !SocialGoUtils.isAnyEmpty(entity.getTitle(), entity.getSummary())
        if (!valid) {
            sErrMsgRef = ErrMsgRef("title or summary invalid", entity)
        }
        return valid
    }

    private fun isSummaryValid(entity: ShareEntity): Boolean {
        val valid = !SocialGoUtils.isAnyEmpty(entity.getSummary())
        if (!valid) {
            sErrMsgRef = ErrMsgRef("summary invalid", entity)
        }
        return valid
    }

    // url 合法
    private fun isUrlValid(entity: ShareEntity): Boolean {
        val targetUrl = entity.getUrl()
        val urlValid = !SocialGoUtils.isAnyEmpty(targetUrl) && SocialGoUtils.isHttpPath(targetUrl)
        if (!urlValid) {
            sErrMsgRef = ErrMsgRef("url invalid", entity)
        }
        return urlValid
    }

    // 本地图片存在
    private fun isLocalImageValid(obj: ShareEntity): Boolean {
        val thumbImagePath = obj.getImagePath()
        val exist = SocialGoUtils.isExist(thumbImagePath)
        val picFile = SocialGoUtils.isPicFile(thumbImagePath)
        if (!exist || !picFile) {
            sErrMsgRef = ErrMsgRef("file ${if (exist) "" else "not exist"} ${if(picFile) "" else "not is image"}" , obj)
        }
        return exist && picFile
    }
}
