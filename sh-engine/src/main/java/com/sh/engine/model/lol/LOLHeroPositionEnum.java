package com.sh.engine.model.lol;

import com.google.common.collect.Lists;

import java.util.List;

/**
 * 在击杀细节中，扮演的角色
 *
 * @Author caiwen
 * @Date 2024 03 23 22 06
 **/
public enum LOLHeroPositionEnum {
    /**
     * 敌方助攻
     */
    E_ASSIST(0, "E_ASSIST"),

    /**
     * 敌方击杀我方
     */
    E_KILL(1, "E-KILL"),

    /**
     * 队友被击杀
     */
    T_KILLED(2, "T-KILLED"),

    /**
     * 队友助攻
     */
    T_ASSIST(3, "T-ASSIST"),

    /**
     * 我击杀敌方
     */
    MYSELF_KILL(4, "MYSELF_KILL"),

    /**
     * 地方被杀
     */
    E_KILLED(5, "E-KILLED"),

    /**
     * 我助攻
     */
    MYSELF_ASSIST(6, "MYSELF_ASSIST"),

    /**
     * 队友击杀
     */
    T_KILL(7, "T-KILL"),

    /**
     * 我被击杀
     */
    MYSELF_KILLED(8, "MYSELF_KILLED"),

    /**
     * 怪物被击杀
     */
    MONSITER(10, "MONSITER"),
    ;

    private LOLHeroPositionEnum(int id, String name) {
        this.labelId = id;
        this.name = name;
    }

    private int labelId;
    private String name;

    public int getLabelId() {
        return labelId;
    }

    public void setLabelId(int labelId) {
        this.labelId = labelId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public static LOLHeroPositionEnum of(int id) {
        for (LOLHeroPositionEnum heroPositionEnum : LOLHeroPositionEnum.values()) {
            if (heroPositionEnum.getLabelId() == id) {
                return heroPositionEnum;
            }
        }
        return null;
    }

    /**
     * 我击杀敌方
     *
     * @return
     */
    private static List<Integer> findMyselfKillPair() {
        return Lists.newArrayList(
                LOLHeroPositionEnum.T_ASSIST.getLabelId(),
                LOLHeroPositionEnum.MYSELF_KILL.getLabelId(),
                LOLHeroPositionEnum.E_KILLED.getLabelId()
        );
    }

    /**
     * 队友击杀敌方，我助攻
     *
     * @return
     */
    private static List<Integer> findMyselfAssistPair() {
        return Lists.newArrayList(
                LOLHeroPositionEnum.T_KILL.getLabelId(),
                LOLHeroPositionEnum.MYSELF_ASSIST.getLabelId(),
                LOLHeroPositionEnum.E_KILLED.getLabelId()
        );
    }

    /**
     * yolo识别结果可能有误，做一下过滤
     * 只要跟我相关的
     *
     * @param labelIds
     * @return
     */
    public static List<Integer> filter(List<Integer> labelIds) {
        if (labelIds == null || labelIds.size() == 0) {
            return Lists.newArrayList();
        }

        List<Integer> res = Lists.newArrayList();
        List<Integer> pairCodes = Lists.newArrayList();
        if (labelIds.contains(MYSELF_ASSIST.getLabelId())) {
            pairCodes = findMyselfAssistPair();
        } else if (labelIds.contains(MYSELF_KILL.getLabelId())) {
            pairCodes = findMyselfKillPair();
        }

        for (Integer labelId : labelIds) {
            if (pairCodes.contains(labelId)) {
                res.add(labelId);
            }
        }
        return res;
    }
}
