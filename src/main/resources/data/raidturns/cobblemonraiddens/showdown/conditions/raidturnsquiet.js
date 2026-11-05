{
    name: 'raidturnsquiet',
    noCopy: true,
    onDamagePriority: 1000,
    onDamage() {
        return false;
    },
    onTryHealPriority: 1000,
    onTryHeal() {
        return false;
    },
}
