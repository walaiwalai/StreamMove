package com.sh.engine.plugin.lol;

import com.google.common.collect.Lists;

import java.util.List;

/**
 * @Author caiwen
 * @Date 2024 03 23 22 06
 **/
public enum LOLHeroPositionEnum {
    E_ASSIST(0, "E-ASSIST"),
    E_KILL(1, "E-KILL"),
    T_KILLED(2, "T-KILLED"),
    T_ASSIST(3, "T-ASSIST"),
    MYSELF_KILL(4, "MYSELF_KILL"),
    E_KILLED(5, "E-KILLED"),
    MYSELF_ASSIST(6, "MYSELF_ASSIST"),
    T_KILL(7, "T-KILL"),
    MYSELF_KILLED(8, "MYSELF_KILLED"),
    MONSITER(9, "MONSITER"),
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

    private static List<Integer> findTKillPair() {
        return Lists.newArrayList(
                LOLHeroPositionEnum.T_ASSIST.getLabelId(),
                LOLHeroPositionEnum.E_KILLED.getLabelId(),
                LOLHeroPositionEnum.T_KILL.getLabelId(),
                LOLHeroPositionEnum.MYSELF_ASSIST.getLabelId()
        );
    }

    private static List<Integer> findEKillPair() {
        return Lists.newArrayList(
                LOLHeroPositionEnum.E_ASSIST.getLabelId(),
                LOLHeroPositionEnum.T_KILLED.getLabelId(),
                LOLHeroPositionEnum.E_KILL.getLabelId(),
                LOLHeroPositionEnum.MYSELF_KILLED.getLabelId()
        );
    }

    private static List<Integer> findMyselfKillPair() {
        return Lists.newArrayList(
                LOLHeroPositionEnum.T_ASSIST.getLabelId(),
                LOLHeroPositionEnum.MYSELF_KILL.getLabelId(),
                LOLHeroPositionEnum.E_KILLED.getLabelId()
        );
    }

    public static List<LOLHeroPositionEnum> filter(List<Integer> labelIds) {
        if (labelIds == null || labelIds.size() == 0) {
            return Lists.newArrayList();
        }

        List<LOLHeroPositionEnum> res = Lists.newArrayList();
        List<Integer> pairCodes;
        if (labelIds.contains(T_KILL.getLabelId())) {
            pairCodes = findTKillPair();
        } else if (labelIds.contains(MYSELF_KILL.getLabelId())) {
            pairCodes = findMyselfKillPair();
        } else {
            pairCodes = findEKillPair();
        }

        for (Integer labelId : labelIds) {
            if (pairCodes.contains(labelId)) {
                res.add(LOLHeroPositionEnum.of(labelId));
            }
        }
        return res;
    }
}
