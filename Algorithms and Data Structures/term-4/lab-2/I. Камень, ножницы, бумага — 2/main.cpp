//
// Created by Владимир on 24.06.2021.
//

#include <iostream>

int main() {

    int stones1, scissors1, papers1;
    int stones2, scissors2, papers2;

    std::cin >> stones1 >> scissors1 >> papers1;
    std::cin >> stones2 >> scissors2 >> papers2;

    int scissorsLost = scissors1 - scissors2 - stones2;
    int stonesLost = stones1 - stones2 - papers2;
    int papersLost = papers1 - papers2 - scissors2;

    std::cout << std::max(0, std::max(scissorsLost, std::max(stonesLost, papersLost)));

    return 0;
}