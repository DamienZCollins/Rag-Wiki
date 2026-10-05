/**
 * Agent 内核模块。
 * 职责：typed Op / Event 协议、双层主循环、工具注册与调用、会话与事件存储、售后动作。
 * 对外协作：通过本包的 api 包暴露能力，供 eval 驱动；检索与快通道能力从 rag.api、wiki.api 获取。
 */
package com.ragwiki.agent;
