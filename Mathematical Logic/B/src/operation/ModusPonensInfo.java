package operation;

public class ModusPonensInfo {
    private boolean isModusPonens;
    private int leftMP;
    private int rightMP;

    public void setModusPonens(boolean modusPonens) {
        isModusPonens = modusPonens;
    }

    public boolean isModusPonens() {
        return isModusPonens;
    }

    public void setLeftMP(int leftMP) {
        this.leftMP = leftMP;
    }

    public void setRightMP(int rightMP) {
        this.rightMP = rightMP;
    }

    public int getLeftMP() {
        return leftMP;
    }

    public int getRightMP() {
        return rightMP;
    }
}
