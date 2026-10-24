package app.kinsight.core.model

/**
 * The family member's mascot (animal avatars spec 2026-10-24): assigned once per member from the group's membership
 * (core/data Mascots.assign), the same on every phone. The order is part of the assignment: never reorder, only add.
 */
enum class Animal { CAT, DOG, FOX, PANDA, RABBIT, BEAR, OWL, PENGUIN, FROG, RACCOON }

/** How the mascot feels about today's screen time against the day limit, happiest first (core/data Mascots.moodOf). */
enum class Mood { HAPPY, CONTENT, CALM, WORRIED, UPSET, UNWELL }
