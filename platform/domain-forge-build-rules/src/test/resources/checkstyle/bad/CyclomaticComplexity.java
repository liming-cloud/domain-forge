package fixture.checkstyle;

/**
 * 反例：一个方法 10 个判断分支加三元表达式，圈复杂度 12。
 */
public class CyclomaticComplexity {
    int grade(int score) {
        if (score > 90) {
            return 1;
        }
        if (score > 80) {
            return 2;
        }
        if (score > 70) {
            return 3;
        }
        if (score > 60) {
            return 4;
        }
        if (score > 50) {
            return 5;
        }
        if (score > 40) {
            return 6;
        }
        if (score > 30) {
            return 7;
        }
        if (score > 20) {
            return 8;
        }
        if (score > 10) {
            return 9;
        }
        if (score > 5) {
            return 10;
        }
        return score > 0 ? 11 : 12;
    }
}
