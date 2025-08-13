package com.sh.engine.model.lol;

import com.google.common.collect.Lists;
import com.sh.engine.model.video.VideoSnapPoint;
import lombok.Data;
import org.apache.commons.collections4.CollectionUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * @Author caiwen
 * @Date 2024 01 13 23 26
 **/
@Data
public class LoLPicData {
    private int K;
    private int D;
    private int A;
    private Integer targetIndex;
    private VideoSnapPoint source;

    private HeroKillOrAssistDetail heroKADetail;

    public LoLPicData() {
    }

    public LoLPicData(int k, int d, int a) {
        K = k;
        D = d;
        A = a;
    }

    public LoLPicData copy() {
        return new LoLPicData(this.K, this.D, this.A);
    }

    /**
     * 合并识别的击杀细节框，每一行表示一次击杀详情
     *
     * @return
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
                .sorted(Comparator.comparing(p -> p.getPosition().get(3)))
                .collect(Collectors.toList());
        List<List<Integer>> labelIdPerKills = Lists.newArrayList();
        Float lastMaxYPosition = null;
        for (int i = 0; i < sortedProfiles.size(); i++) {
            HeroProfile heroProfile = sortedProfiles.get(i);
            if (lastMaxYPosition != null && Math.abs(lastMaxYPosition - heroProfile.getPosition().get(3)) < 5f) {
                // 小于5属于同一行，说明是同一次击杀
                labelIdPerKills.get(labelIdPerKills.size() - 1).add(heroProfile.getLabelId());
            } else {
                labelIdPerKills.add(Lists.newArrayList(heroProfile.getLabelId()));
            }

            lastMaxYPosition = heroProfile.getPosition().get(3);
        }

        return labelIdPerKills.stream().map(LOLHeroPositionEnum::filter).filter(CollectionUtils::isNotEmpty)
                .collect(Collectors.toList());

    }

    public static LoLPicData genBlank() {
        return new LoLPicData(-1, -1, -1);
    }

    public static LoLPicData genInvalid() {
        return new LoLPicData(-2, -2, -2);
    }

    public boolean beInvalid() {
        return this.K == -2;
    }

    public boolean beBlank() {
        return this.K == -1;
    }

    public boolean beValid() {
        return this.K >= 0;
    }

    public boolean compareKda(LoLPicData other) {
        if (other == null) {
            return false;
        }
        return Objects.equals(this.K, other.K) && Objects.equals(this.D, other.D) && Objects.equals(this.A, other.A);
    }

    public static class HeroKillOrAssistDetail {
        private List<List<Float>> boxes;

        /**
         * @see LOLHeroPositionEnum
         */
        private List<Integer> labelIds;

        public List<List<Float>> getBoxes() {
            return boxes;
        }

        public HeroKillOrAssistDetail(List<List<Float>> boxes, List<Integer> labelIds) {
            this.boxes = boxes;
            this.labelIds = labelIds;
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

    static class HeroProfile {
        private List<Float> position;
        private int labelId;

        public List<Float> getPosition() {
            return position;
        }

        public void setPosition(List<Float> position) {
            this.position = position;
        }

        public int getLabelId() {
            return labelId;
        }

        public void setLabelId(int labelId) {
            this.labelId = labelId;
        }
    }
}
