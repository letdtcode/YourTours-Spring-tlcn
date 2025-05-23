package com.hcmute.yourtours.models.room_categories;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hcmute.yourtours.enums.CommonStatusEnum;
import com.hcmute.yourtours.models.common.NameDataModel;
import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import javax.validation.constraints.NotNull;
import java.util.UUID;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class RoomCategoryInfo extends NameDataModel<UUID> {

    @NotNull
    private Boolean important;

    @NotNull
    private Boolean configBed;

    private CommonStatusEnum status;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Long numberOfHomes;
}
