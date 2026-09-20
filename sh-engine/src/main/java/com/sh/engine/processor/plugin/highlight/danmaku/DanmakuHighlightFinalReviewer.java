package com.sh.engine.processor.plugin.highlight.danmaku;

import com.alibaba.fastjson.JSONObject;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.engine.model.danmaku.HighlightFactVerification;
import com.sh.engine.model.danmaku.HighlightPayoffAssessment;
import com.sh.engine.model.danmaku.HighlightPublicationAssessment;
import com.sh.engine.model.danmaku.VisualEvidenceBatch;
import com.sh.engine.model.llm.LlmCallResult;
import com.sh.engine.model.llm.LlmResponseException;
import com.sh.engine.service.LlmService;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.util.function.Predicate;

/**
 * 将密集候选的事实提取、实质看点分类、发布质量评审和独立否决拆成单一职责请求。
 */
@Component
public class DanmakuHighlightFinalReviewer {
    private static final String FACT_CACHE_VERSION = "fact-v9-continuity-wording";
    private static final String PAYOFF_CACHE_VERSION =
            "payoff-v7-emotion-distinctive-protocol";
    private static final String PUBLICATION_CACHE_VERSION =
            "publication-v9-protocol-complete-fingerprint";
    private static final String CONFIRMATION_CACHE_VERSION =
            "publication-confirm-v3-protocol-complete-veto";

    @Resource
    private LlmService llmService;
    @Resource
    private HighlightAnalysisCache analysisCache;
    @Resource
    private HighlightAnalysisAuditRepository auditRepository;

    /**
     * 直接查看密集原始帧，只提取证据能够证明的事件事实，不接收稀疏初判。
     */
    public HighlightFactVerification verifyFacts(
            String streamerName,
            String segmentKey,
            File recordDirectory,
            String evidenceContext,
            VisualEvidenceBatch visualEvidence) {
        String cacheKey = FACT_CACHE_VERSION + "-" + segmentKey;
        HighlightFactVerification cached = analysisCache.getFactVerification(
                streamerName, cacheKey);
        String prompt = buildFactPrompt(evidenceContext, visualEvidence);
        if (cached != null) {
            cached.normalizeProtocolValues();
            auditRepository.append(recordDirectory, "fact-verification-cache", cacheKey,
                    prompt, null, cached, visualEvidence);
            return cached;
        }
        HighlightFactVerification result = requestFacts(
                recordDirectory, "fact-verification-vision", cacheKey,
                prompt, visualEvidence);
        if (result.requiresIndependentRecallPass()) {
            String recallPrompt = buildIndependentRecallPrompt(
                    evidenceContext, visualEvidence);
            result = requestFacts(
                    recordDirectory, "fact-verification-independent-recall",
                    cacheKey, recallPrompt, visualEvidence);
        }
        analysisCache.saveFactVerification(streamerName, cacheKey, result);
        return result;
    }

    private HighlightFactVerification requestFacts(
            File recordDirectory,
            String stage,
            String cacheKey,
            String prompt,
            VisualEvidenceBatch visualEvidence) {
        AuditCall auditCall = new AuditCall(
                recordDirectory, stage, cacheKey, prompt, visualEvidence);
        LlmCallResult<HighlightFactVerification> call = callWithProtocolRetry(
                auditCall, () -> llmService.analyzeImagesWithRaw(
                        prompt, visualEvidence.toLlmInputs(),
                        HighlightFactVerification.class));
        HighlightFactVerification result = requireResult(
                call.getResult(), "fact verification");
        result.normalizeProtocolValues();
        auditRepository.append(recordDirectory, stage, cacheKey,
                prompt, call.getRawResponse(), result, visualEvidence);
        return result;
    }

    /** 只判断精剪片段呈现了什么实质看点，不同时承担事实对齐、节奏和包装职责。 */
    public HighlightPayoffAssessment assessPayoff(
            String streamerName,
            String segmentKey,
            File recordDirectory,
            String evidenceContext,
            HighlightFactVerification facts,
            VisualEvidenceBatch visualEvidence) {
        String prompt = buildPayoffPrompt(evidenceContext, facts, visualEvidence);
        String cacheKey = buildAssessmentCacheKey(
                PAYOFF_CACHE_VERSION, segmentKey, prompt, visualEvidence);
        HighlightPayoffAssessment cached = analysisCache.getPayoffAssessment(
                streamerName, cacheKey);
        if (cached != null) {
            cached.normalizeProtocolValues();
        }
        if (cached != null && cached.hasCompleteProtocol()) {
            auditRepository.append(recordDirectory, "payoff-review-cache", cacheKey,
                    prompt, null, cached, visualEvidence);
            return cached;
        }
        auditIncompleteCache(recordDirectory, "payoff-review", cacheKey,
                prompt, cached, visualEvidence);
        AuditCall auditCall = new AuditCall(
                recordDirectory, "payoff-review", cacheKey, prompt, visualEvidence);
        LlmCallResult<HighlightPayoffAssessment> call = callWithProtocolRetry(
                auditCall, () -> requireCompleteProtocol(
                        llmService.analyzeImagesWithRaw(
                                prompt, visualEvidence.toLlmInputs(),
                                HighlightPayoffAssessment.class),
                        HighlightPayoffAssessment::hasCompleteProtocol,
                        "payoff review"));
        HighlightPayoffAssessment result = requireResult(
                call.getResult(), "payoff review");
        result.normalizeProtocolValues();
        auditRepository.append(recordDirectory, "payoff-review", cacheKey,
                prompt, call.getRawResponse(), result, visualEvidence);
        analysisCache.savePayoffAssessment(streamerName, cacheKey, result);
        return result;
    }

    /**
     * 只在事实通过本地硬校验后判断观看价值；已确认事实是允许用于标题的上限。
     */
    public HighlightPublicationAssessment reviewPublication(
            String streamerName,
            String segmentKey,
            File recordDirectory,
            String evidenceContext,
            HighlightFactVerification facts,
            HighlightPayoffAssessment payoff,
            VisualEvidenceBatch visualEvidence) {
        String prompt = buildPublicationPrompt(
                evidenceContext, facts, payoff, visualEvidence);
        String cacheKey = buildPublicationCacheKey(segmentKey, prompt, visualEvidence);
        HighlightPublicationAssessment cached = analysisCache.getPublicationAssessment(
                streamerName, cacheKey);
        if (cached != null) {
            cached.normalizeProtocolValues();
        }
        if (cached != null && cached.hasCompleteProtocol()) {
            cached.setPayoff(payoff);
            auditRepository.append(recordDirectory, "publication-review-cache", cacheKey,
                    prompt, null, cached, visualEvidence);
            return cached;
        }
        auditIncompleteCache(recordDirectory, "publication-review", cacheKey,
                prompt, cached, visualEvidence);
        AuditCall auditCall = new AuditCall(recordDirectory,
                "publication-review", cacheKey, prompt, visualEvidence);
        LlmCallResult<HighlightPublicationAssessment> call = callWithProtocolRetry(
                auditCall, () -> requireCompleteProtocol(
                        llmService.analyzeImagesWithRaw(
                                prompt, visualEvidence.toLlmInputs(),
                                HighlightPublicationAssessment.class),
                        HighlightPublicationAssessment::hasCompleteProtocol,
                        "publication review"));
        HighlightPublicationAssessment result = requireResult(
                call.getResult(), "publication review");
        result.setPayoff(payoff);
        result.normalizeProtocolValues();
        result.normalizeCopyLengths();
        auditRepository.append(recordDirectory, "publication-review", cacheKey,
                prompt, call.getRawResponse(), result, visualEvidence);
        analysisCache.savePublicationAssessment(streamerName, cacheKey, result);
        return result;
    }

    /**
     * 对初审通过的精剪执行一次不接收初审结论的独立否决复核。
     */
    public HighlightPublicationAssessment confirmPublication(
            String streamerName,
            String segmentKey,
            File recordDirectory,
            String evidenceContext,
            HighlightFactVerification facts,
            HighlightPayoffAssessment payoff,
            VisualEvidenceBatch visualEvidence) {
        String prompt = buildPublicationConfirmationPrompt(
                evidenceContext, facts, payoff, visualEvidence);
        String cacheKey = buildAssessmentCacheKey(
                CONFIRMATION_CACHE_VERSION, segmentKey, prompt, visualEvidence);
        HighlightPublicationAssessment cached = analysisCache.getPublicationAssessment(
                streamerName, cacheKey);
        if (cached != null) {
            cached.normalizeProtocolValues();
        }
        if (cached != null && cached.hasCompleteProtocol()) {
            cached.setPayoff(payoff);
            auditRepository.append(recordDirectory, "publication-confirmation-cache",
                    cacheKey, prompt, null, cached, visualEvidence);
            return cached;
        }
        auditIncompleteCache(recordDirectory, "publication-confirmation", cacheKey,
                prompt, cached, visualEvidence);
        AuditCall auditCall = new AuditCall(recordDirectory,
                "publication-confirmation", cacheKey, prompt, visualEvidence);
        LlmCallResult<HighlightPublicationAssessment> call = callWithProtocolRetry(
                auditCall, () -> requireCompleteProtocol(
                        llmService.analyzeImagesWithRaw(
                                prompt, visualEvidence.toLlmInputs(),
                                HighlightPublicationAssessment.class),
                        HighlightPublicationAssessment::hasCompleteProtocol,
                        "publication confirmation"));
        HighlightPublicationAssessment result = requireResult(
                call.getResult(), "publication confirmation");
        result.setPayoff(payoff);
        result.normalizeProtocolValues();
        result.normalizeCopyLengths();
        auditRepository.append(recordDirectory, "publication-confirmation", cacheKey,
                prompt, call.getRawResponse(), result, visualEvidence);
        analysisCache.savePublicationAssessment(streamerName, cacheKey, result);
        return result;
    }

    private String buildPublicationCacheKey(
            String segmentKey, String prompt, VisualEvidenceBatch visualEvidence) {
        return buildAssessmentCacheKey(
                PUBLICATION_CACHE_VERSION, segmentKey, prompt, visualEvidence);
    }

    private String buildAssessmentCacheKey(
            String version,
            String segmentKey,
            String prompt,
            VisualEvidenceBatch visualEvidence) {
        StringBuilder canonicalInput = new StringBuilder(prompt);
        visualEvidence.getFrames().forEach(frame -> canonicalInput.append('|')
                .append(frame.getTimestampSeconds()).append(':')
                .append(frame.getSha256()));
        String fingerprint = DigestUtils.sha256Hex(canonicalInput.toString())
                .substring(0, 24);
        return version + "-" + segmentKey + "-" + fingerprint;
    }

    private String buildFactPrompt(
            String evidenceContext, VisualEvidenceBatch visualEvidence) {
        return "你是直播候选片段的事实验真员，不负责判断是否精彩，也不评分、写标题或迎合前序结论。"
                + "你正在直接查看按时间排列的原始图片。原始图片时间映射："
                + visualEvidence.buildFrameIndexText() + "。\n\n"
                + evidenceContext
                + "\n只寻找一个能由证据直接证明的完整子事件。ASR只证明说了什么，OCR只证明界面文字，"
                + "图片只证明采样时刻的可见状态，弹幕只证明观众反应。不得把口播中的死亡、成功、失败"
                + "当成画面事实；不得把两个界面数值的变化解释成结算、损失或收益，除非界面直接说明"
                + "该字段的含义和所处阶段；不得仅因时间相邻就把无关的动作与结果写成同一事件。一个事实句中的"
                + "主体、动作和对象必须由所引用证据共同直接支持；不得把较早图片中的主体带入后续"
                + "图片，也不得把分开出现的对象与场景拼成同一动作。\n"
                + "setup、action、outcome必须分别给出ASR、OCR或VISION一手证据编号；reaction可以为空。"
                + "setup只描述理解关键动作所必需的直接铺垫，setupEvidence优先引用动作前最近的有效证据，"
                + "不得把候选开头持续存在但与事件无关的界面或背景当成铺垫。"
                + "若填写reaction，reactionEvidence必须引用ASR、VISION或DANMAKU稳定编号，不能填写时间"
                + "或自造编号；DANMAKU只能证明观众反应，不能证明铺垫、动作、结果或事件发生时间。"
                + "subjectResolved表示关键主体明确；outcomeMeaningResolved表示结果状态或数值的含义明确；"
                + "eventContinuityResolved表示行动与结果属于同一连续事件。claimsCausalLink表示描述是否"
                + "声称因果，声称因果时只有直接证据才能令causalLinkResolved=true。完整事件只要求可确认的"
                + "时间顺序和连续性，不要求证明前一动作导致后一结果；可以用“随后”“之后”中性描述先后。"
                + "action既可以是人物的可见动作，也可以是事件本身的可见发展或状态转换，不要求证据"
                + "能够证明操作者意图。setup可以是动作前直接可见的初始状态；outcome可以是后续画面或"
                + "文字明确标示的结果状态。仅仅时间相邻不足以证明两个无关内容属于同一事件；但同一源"
                + "视频内按时间排列的密集帧若直接显示连续状态发展、界面阶段推进或明确结果页，即使中间"
                + "发生正常镜头切换，也属于连续性证据，不能仅因不知道切换原因或事件成因而判不连续。"
                + "当动作与结果在连续时间线上依次可见但原因未知时，claimsCausalLink=false、"
                + "causalLinkResolved=false，未知原因放入limitations，不得仅因缺少因果证据放入"
                + "blockingIssues或删除已直接可见的outcome。blockingIssues只填写"
                + "会使当前setup、action或outcome主张无法成立的疑点；未被当前主张使用的背景原因、操作动机、"
                + "额外因果或更早过程放入limitations，不能混入blockingIssues。两个字段即使没有内容也必须"
                + "输出空数组，不能为了形成故事而隐藏真正的阻断项。coverTimestamp只能选择关键动作或结果"
                + "附近的清晰真实帧。\n"
                + "只输出JSON：setup、setupEvidence、action、actionEvidence、outcome、outcomeEvidence、"
                + "reaction、reactionEvidence、subjectResolved、actionDirectlySupported、"
                + "outcomeDirectlySupported、outcomeMeaningResolved、eventContinuityResolved、"
                + "claimsCausalLink、causalLinkResolved、blockingIssues、limitations、coverTimestamp。"
                + "四个Evidence字段均为"
                + "对象数组，每项只能包含evidenceId。没有完整事件时文本置空、数组置空、相关布尔值为false，"
                + "并在blockingIssues中写明无法组成完整事件的具体证据缺口，禁止无解释的全空返回。";
    }

    private String buildIndependentRecallPrompt(
            String evidenceContext, VisualEvidenceBatch visualEvidence) {
        return "你是独立的直播事件事实召回员。另一位审核员没有形成可通过结构校验的事件结论；"
                + "这不代表候选中没有事件。请不要猜测其答案，也不要沿用其否定结论，重新直接查看"
                + "按时间排列的原始图片。原始图片时间映射："
                + visualEvidence.buildFrameIndexText() + "。\n\n"
                + evidenceContext
                + "\n你的职责是降低漏检，只选择证据最完整的一个子事件，不判断它是否精彩。"
                + "setup、action、outcome是叙事阶段，不要求action必须是人物主动操作：连续画面中的"
                + "可见发展、状态转换或界面阶段推进也可以作为action。明确文字结果页可直接证明结果"
                + "状态，但不得反推未显示的原因。正常镜头切换不等于录像或事件中断；若密集帧按时间"
                + "直接呈现初始状态、发展和明确结果，应令eventContinuityResolved=true，同时在原因"
                + "未知且未声称因果时令claimsCausalLink=false、causalLinkResolved=false。只有画面"
                + "主体、事件阶段或时间线确实互不相关时，才以blockingIssues否定连续性。\n"
                + "ASR只证明说了什么，OCR只证明界面文字，图片只证明采样时刻的可见状态，弹幕只证明"
                + "观众反应。不得补写证据中没有的主体、动作、对象、原因、结果或题材知识。setup、"
                + "action、outcome必须分别引用ASR、OCR或VISION稳定编号；reaction可以为空且只能引用"
                + "ASR、VISION或DANMAKU。coverTimestamp选择关键发展或结果附近的清晰真实帧。\n"
                + "只输出JSON：setup、setupEvidence、action、actionEvidence、outcome、outcomeEvidence、"
                + "reaction、reactionEvidence、subjectResolved、actionDirectlySupported、"
                + "outcomeDirectlySupported、outcomeMeaningResolved、eventContinuityResolved、"
                + "claimsCausalLink、causalLinkResolved、blockingIssues、limitations、coverTimestamp。"
                + "四个Evidence字段均为对象数组，每项只能包含evidenceId；blockingIssues和limitations"
                + "即使为空也必须输出空数组。没有完整事件时，明确写出缺失的是哪一段以及哪些一手证据"
                + "发生冲突，不能用笼统的‘来源之间不存在共同支持关系’代替逐项检查。";
    }

    private String buildPayoffPrompt(
            String evidenceContext,
            HighlightFactVerification facts,
            VisualEvidenceBatch visualEvidence) {
        return "你只负责判断一段精剪视频是否呈现了实质看点，不做事实提取、事实对齐、节奏总评、"
                + "标题或封面文案。你看不到召回分数和其他审核答案。下方verifiedFacts是允许使用的事实"
                + "上限；请直接查看按时间排列的最终精剪真实图片，图片时间映射："
                + visualEvidence.buildFrameIndexText() + "。\n\n【verifiedFacts】\n"
                + buildVerifiedStory(facts) + "\n\n【精剪内一手证据】\n" + evidenceContext
                + "\ncategory只能是SPECTACLE、SKILL、REVERSAL、COMEDY、EMOTION、INTERACTION、"
                + "TENSION、INFORMATIONAL或ROUTINE。先指出一个具体、可定位的dominantMoment，并用payoffEvidence"
                + "引用成片内直接支持它的ASR、OCR或VISION编号；弹幕不能作为唯一证据。找不到这样一个"
                + "主导兑现时刻时必须归ROUTINE。多个普通镜头、场景或界面依次出现，不会因为数量多而组成"
                + "兑现点。ordinaryProcessOnly在内容只是普通移动、搜索、等待、菜单、地图、配装、设置、"
                + "加载、结果页停留或这些画面之间的切换时必须为true，此时meaningful=false。完整结果页"
                + "可以支持此前已在精剪中直接呈现的结果，但不能单独充当兑现点；紧接可见动作出现的"
                + "即时状态或文字提示，在主体和归属明确时可以作为该动作结果的证据。\n"
                + "SPECTACLE必须在某个明确时刻直接出现高冲击的动态、规模、构图或显著状态爆发，令"
                + "spectacleVisualImpact=true；普通镜头切换、视角移动、开关界面以及从正常画面进入结果页"
                + "都不算视觉奇观，不能靠罗列‘先出现A、再出现B’证明。若冲击主要来自软件自动播放的"
                + "标准动画、过场或固定结果演出，systemControlledPresentation=true且不得归为"
                + "SPECTACLE。SKILL必须直接呈现可辨认的参与者动作与可归因结果，并令"
                + "participantActionAndResultVisible=true；动作后的即时结果提示可以佐证结果，但只出现"
                + "提示而没有直接动作不算技巧。REVERSAL必须先在成片内用直接证据建立明确"
                + "预期、优势或稳定状态，随后同一事件直接推翻它；分别在reversalExpectationEvidence和"
                + "reversalContradictionEvidence引用前后证据，并令reversalExpectationEstablished=true。"
                + "仅有一次尝试、孤立的正面口头词、"
                + "普通行动后出现失败/成功页，或互不相干的前后画面，不构成反转。COMEDY、EMOTION、"
                + "INTERACTION必须在音画中先建立具体情境，再出现与该情境直接对应的可辨认反应或结果，"
                + "并令completedSetupAndPayoff=true。普通事实也可能形成这三类看点，但必须有完整铺垫和"
                + "强匹配的口头或可见反应；单个动作、孤立回答或只有弹幕附和不够。EMOTION还必须呈现"
                + "明显超出日常开心、失望、抱怨或短暂肢体动作的情绪升级，令"
                + "distinctiveEmotionalEscalation=true且strength至少为70。"
                + "TENSION必须在同一精剪内先建立明确风险、冲突或持续不确定状态，再经过可见升级并得到"
                + "明确解决；分别在tensionSetupEvidence和tensionResolutionEvidence引用前后证据，并令"
                + "escalatingConflictAndResolution=true。只有普通移动、重复尝试或没有解决的风险不算。"
                + "INFORMATIONAL必须在成片内直接给出观众可以复述和应用的方法、含义明确的对比、规则或"
                + "结论，payoffEvidence中必须包含实际讲解该结论的ASR证据。短暂出现、查看详情、未经"
                + "解释的数值变化、未兑现口播和只有弹幕活跃的流程也归"
                + "ROUTINE。\n"
                + "standalonePayoff表示无需候选窗外围上下文，仅看精剪就能感知明确兑现点。"
                + "dominantMomentDirectlySupported只在音画证据无需推断就能证明兑现时为true。"
                + "类别专属字段只在对应类别填写，其他类别可置null或空数组：REVERSAL必须填写"
                + "reversalExpectationEstablished和两个反转证据数组；SPECTACLE必须填写"
                + "spectacleVisualImpact和systemControlledPresentation，不能因动画华丽而把后者置false；"
                + "SKILL必须填写participantActionAndResultVisible；COMEDY、EMOTION、INTERACTION必须"
                + "填写completedSetupAndPayoff，EMOTION还必须填写distinctiveEmotionalEscalation；"
                + "TENSION必须填写escalatingConflictAndResolution以及"
                + "两个紧张事件证据数组；INFORMATIONAL必须填写reusableInformation，并须在"
                + "reusableTakeaway写出成片直接给出的具体结论。strength为0~100整数，只评价兑现点"
                + "本身的强度。弹幕只能增强已经由音画成立的看点，不能单独制造看点。\n"
                + "只输出JSON：category、meaningful、strength、dominantMoment、"
                + "payoffEvidence、dominantMomentDirectlySupported、ordinaryProcessOnly、"
                + "reversalExpectationEstablished、reversalExpectationEvidence、"
                + "reversalContradictionEvidence、spectacleVisualImpact、"
                + "systemControlledPresentation、participantActionAndResultVisible、"
                + "completedSetupAndPayoff、distinctiveEmotionalEscalation、"
                + "escalatingConflictAndResolution、"
                + "tensionSetupEvidence、tensionResolutionEvidence、standalonePayoff、"
                + "reusableInformation、reusableTakeaway。payoffEvidence是对象数组，每项只能包含"
                + "evidenceId；两个反转证据字段也使用相同对象格式。ROUTINE时dominantMoment置空且"
                + "三个证据数组均为空数组。弹幕只能证明观众预期或反应，不能证明客观动作和结果。";
    }

    private String buildPublicationPrompt(
            String evidenceContext,
            HighlightFactVerification facts,
            HighlightPayoffAssessment payoff,
            VisualEvidenceBatch visualEvidence) {
        return "你负责最终精剪的事实对齐、观看节奏和发布包装，不重新分类看点。verifiedFacts是允许使用"
                + "的事实主张上限，verifiedPayoff是独立看点评估；不得新增或改写其主体、对象、动作、"
                + "结果、因果、类别或可复用结论。你正在直接查看待发布精剪真实图片，图片时间映射："
                + visualEvidence.buildFrameIndexText() + "。\n\n【verifiedFacts】\n"
                + buildVerifiedStory(facts) + "\n\n【verifiedPayoff】\n"
                + buildVerifiedPayoff(payoff) + "\n\n【精剪内证据】\n" + evidenceContext
                + "\n逐项检查setup、action、outcome是否由精剪内文字证据和真实图片支持，以及是否属于"
                + "同一连续事件。复合句中的每个主体、动作和对象都必须有对应证据；发现不受支持的分句"
                + "写入unsupportedClaims。任一对齐项为false时publishable=false且score不得超过59。"
                + "sameEventSupported只判断中性时间顺序和画面连续性，不要求证明因果。\n"
                + "在不重新评价verifiedPayoff类别和强度的前提下，评估陌生观众能否独立理解以及片段是否"
                + "持续推进。不能因结果画面正常停留数秒就抹掉之前已经完整呈现的兑现点；只按真实无进展"
                + "时长填写longestNoDevelopmentSeconds。quality只包含audienceValue、contentDensity、"
                + "longestNoDevelopmentSeconds、rationale；所有分数为0~100整数。suggestedTitle只能改写"
                + "verifiedFacts，使用8~18个汉字；coverText使用4~12个汉字，必须同时体现"
                + "片段内可见的具体行为/变化和结果/反差，不能只写结果状态、抽象评价或题材名；"
                + "suggestedTitle不超过12字且满足该要求时可直接复用。标题和封面文案不得照抄脏话、"
                + "侮辱性称呼、歧视词或人身攻击；即使ASR或弹幕含有这类词，也必须改成中性事实描述。\n"
                + "只输出JSON：publishable、score、reason、oneSentenceStory、suggestedTitle、coverText、"
                + "factAlignment、quality。factAlignment包含setupSupported、actionSupported、"
                + "outcomeSupported、sameEventSupported、unsupportedClaims、rationale；unsupportedClaims"
                + "没有内容时必须输出空数组。";
    }

    private String buildPublicationConfirmationPrompt(
            String evidenceContext,
            HighlightFactVerification facts,
            HighlightPayoffAssessment payoff,
            VisualEvidenceBatch visualEvidence) {
        return "你是短视频发布前的独立否决审核员。你看不到初审答案和分数，也不重新分类已经独立确认"
                + "的看点。你只检查最终精剪是否真正包含允许使用的事实和看点证据、陌生观众是否能理解、"
                + "是否存在过长无进展，以及包装是否越过事实边界。图片时间映射："
                + visualEvidence.buildFrameIndexText() + "。\n\n【verifiedFacts】\n"
                + buildVerifiedStory(facts) + "\n\n【verifiedPayoff】\n"
                + buildVerifiedPayoff(payoff) + "\n\n【精剪内证据】\n" + evidenceContext
                + "\n不得仅因题材内常见、结果页停留数秒或不知道事件原因而推翻已由精剪音画直接呈现的"
                + "看点；也不得因流程完整、画面清楚或弹幕活跃而放过事实错位、缺乏上下文、长时间停滞"
                + "或包装夸大。任一事实对齐项为false时必须否决。score、audienceValue、contentDensity"
                + "均为0~100整数。suggestedTitle为8~18个汉字；coverText为4~12个汉字，必须"
                + "同时体现片段内可见的具体行为/变化和结果/反差，不能只写结果状态、抽象评价"
                + "或题材名；suggestedTitle不超过12字且满足该要求时可直接复用。标题和封面文案不得"
                + "照抄脏话、侮辱性称呼、歧视词或人身攻击，必须改成中性事实描述。\n"
                + "只输出JSON：publishable、score、reason、oneSentenceStory、suggestedTitle、coverText、"
                + "factAlignment、quality。factAlignment包含setupSupported、actionSupported、"
                + "outcomeSupported、sameEventSupported、unsupportedClaims、rationale；quality包含"
                + "audienceValue、contentDensity、longestNoDevelopmentSeconds、rationale。";
    }

    private String buildVerifiedPayoff(HighlightPayoffAssessment payoff) {
        return JSONObject.toJSONString(payoff);
    }

    private String buildVerifiedStory(HighlightFactVerification facts) {
        JSONObject story = new JSONObject(true);
        story.put("setup", facts.getSetup());
        story.put("action", facts.getAction());
        story.put("outcome", facts.getOutcome());
        story.put("reaction", facts.getReaction());
        story.put("claimsCausalLink", facts.getClaimsCausalLink());
        story.put("causalLinkResolved", facts.getCausalLinkResolved());
        return story.toJSONString();
    }

    private <T> LlmCallResult<T> callWithFailureAudit(
            AuditCall task,
            java.util.function.Supplier<LlmCallResult<T>> request) {
        try {
            return request.get();
        } catch (RuntimeException e) {
            JSONObject failure = new JSONObject(true);
            failure.put("exception", e.getClass().getName());
            failure.put("message", e.getMessage());
            String rawEnvelope = e instanceof LlmResponseException
                    ? ((LlmResponseException) e).getRawEnvelope() : null;
            auditRepository.append(task.recordDirectory, task.stage + "-error",
                    task.cacheKey, task.prompt, rawEnvelope, failure,
                    task.visualEvidence);
            throw e;
        }
    }

    private <T> LlmCallResult<T> callWithProtocolRetry(
            AuditCall task,
            java.util.function.Supplier<LlmCallResult<T>> request) {
        try {
            return callWithFailureAudit(task, request);
        } catch (LlmResponseException firstFailure) {
            AuditCall retry = new AuditCall(
                    task.recordDirectory, task.stage + "-retry", task.cacheKey,
                    task.prompt, task.visualEvidence);
            return callWithFailureAudit(retry, request);
        }
    }

    private <T> LlmCallResult<T> requireCompleteProtocol(
            LlmCallResult<T> call,
            Predicate<T> protocolComplete,
            String stage) {
        T result = call == null ? null : call.getResult();
        if (result == null || !protocolComplete.test(result)) {
            throw new LlmResponseException(
                    "LLM returned incomplete required fields during " + stage,
                    call == null ? null : call.getRawEnvelope());
        }
        return call;
    }

    private void auditIncompleteCache(
            File recordDirectory,
            String stage,
            String cacheKey,
            String prompt,
            Object cached,
            VisualEvidenceBatch visualEvidence) {
        if (cached != null) {
            auditRepository.append(recordDirectory, stage + "-cache-invalid",
                    cacheKey, prompt, null, cached, visualEvidence);
        }
    }

    private <T> T requireResult(T result, String stage) {
        if (result == null) {
            throw new StreamerRecordException(
                    ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR,
                    "LLM returned empty result during " + stage);
        }
        return result;
    }

    /** 单次最终评审调用的审计上下文。 */
    private static final class AuditCall {
        private final File recordDirectory;
        private final String stage;
        private final String cacheKey;
        private final String prompt;
        private final VisualEvidenceBatch visualEvidence;

        private AuditCall(
                File recordDirectory,
                String stage,
                String cacheKey,
                String prompt,
                VisualEvidenceBatch visualEvidence) {
            this.recordDirectory = recordDirectory;
            this.stage = stage;
            this.cacheKey = cacheKey;
            this.prompt = prompt;
            this.visualEvidence = visualEvidence;
        }
    }
}
