package com.sh.engine.model.highlight.lol;

import com.google.common.collect.Lists;
import lombok.Data;
import org.apache.commons.collections4.CollectionUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 单个时间点的 LoL K/D/A，以及仅在 K/A 增加时识别的击杀栏详情。
 */
@Data
public class LoLPicData {
    private int K;
    private int D;
    private int A;
    private HeroKillOrAssistDetail heroKADetail;

    public LoLPicData() {
    }

    public LoLPicData(int k, int d, int a) {
        K = k;
        D = d;
        A = a;
    }

    /**
     * 按检测框的 Y 坐标把英雄头像合并成多行，每一行代表一次击杀记录。
     *
     * @return 每次击杀记录中的英雄位置标签
     */
    public List<List<Integer>> merge2PositionEnum() {
        if (heroKADetail == null) {
            return Lists.newArrayList();
        }

        List<HeroProfile> profiles = Lists.newArrayList();
        for (int i = 0; i < heroKADetail.getBoxes().size(); i++) {
            HeroProfile heroProfile = new HeroProfile();
            heroProfile.setPosition(heroKADetail.getBoxes().get(i));
            heroProfile.setLabelId(heroKADetail.getLabelIds().get(i));
            profiles.add(heroProfile);
        }

        List<HeroProfile> sortedProfiles = profiles.stream()
                .sorted(Comparator.comparing(profile -> profile.getPosition().get(3)))
                .collect(Collectors.toList());
        List<List<Integer>> labelsByKill = Lists.newArrayList();
        Float previousMaxY = null;
        for (HeroProfile profile : sortedProfiles) {
            float currentMaxY = profile.getPosition().get(3);
            if (previousMaxY != null && Math.abs(previousMaxY - currentMaxY) < 5f) {
                labelsByKill.get(labelsByKill.size() - 1).add(profile.getLabelId());
            } else {
                labelsByKill.add(Lists.newArrayList(profile.getLabelId()));
            }
            previousMaxY = currentMaxY;
        }

        return labelsByKill.stream()
                .map(LOLHeroPositionEnum::filter)
                .filter(CollectionUtils::isNotEmpty)
                .collect(Collectors.toList());
    }

    public static LoLPicData genBlank() {
        return new LoLPicData(-1, -1, -1);
    }

    public static LoLPicData genInvalid() {
        return new LoLPicData(-2, -2, -2);
    }

    public boolean beBlank() {
        return K == -1;
    }

    public boolean beValid() {
        return K >= 0;
    }

    public boolean compareKda(LoLPicData other) {
        return other != null
                && Objects.equals(K, other.K)
                && Objects.equals(D, other.D)
                && Objects.equals(A, other.A);
    }

    public static class HeroKillOrAssistDetail {
        private List<List<Float>> boxes;
        /** @see LOLHeroPositionEnum */
        private List<Integer> labelIds;

        public HeroKillOrAssistDetail(List<List<Float>> boxes, List<Integer> labelIds) {
            this.boxes = boxes;
            this.labelIds = labelIds;
        }

        public List<List<Float>> getBoxes() {
            return boxes;
        }

        public void setBoxes(List<List<Float>> boxes) {
            this.boxes = boxes;
        }

        public List<Integer> getLabelIds() {
            return labelIds;
        }

        public void setLabelIds(List<Integer> labelIds) {
            this.labelIds = labelIds;
        }
    }

    private static class HeroProfile {
        private List<Float> position;
        private int labelId;

        private List<Float> getPosition() {
            return position;
        }

        private void setPosition(List<Float> position) {
            this.position = position;
        }

        private int getLabelId() {
            return labelId;
        }

        private void setLabelId(int labelId) {
            this.labelId = labelId;
        }
    }
}
