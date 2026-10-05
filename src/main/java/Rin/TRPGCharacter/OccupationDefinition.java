package Rin.TRPGCharacter;

import java.util.List;

public record OccupationDefinition(
        String id,
        String name,
        List<String> aliases,
        String pointFormula,
        List<String> fixedSkills,
        int restrictedChoiceCount,
        List<String> restrictedChoiceSkills,
        int freeChoiceCount
) {
}
