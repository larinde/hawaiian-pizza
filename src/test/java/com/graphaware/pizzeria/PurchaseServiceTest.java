package com.graphaware.pizzeria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.graphaware.pizzeria.model.Pizza;
import com.graphaware.pizzeria.model.PizzeriaUser;
import com.graphaware.pizzeria.model.Purchase;
import com.graphaware.pizzeria.model.PurchaseState;
import com.graphaware.pizzeria.repository.PizzeriaUserRepository;
import com.graphaware.pizzeria.repository.PurchaseRepository;
import com.graphaware.pizzeria.security.PizzeriaUserPrincipal;
import com.graphaware.pizzeria.service.PizzeriaException;
import com.graphaware.pizzeria.service.PurchaseService;

import java.util.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
public class PurchaseServiceTest {

    @Mock
    private PizzeriaUserRepository userRepository;

    @Mock
    private PurchaseRepository purchaseRepository;

    private PizzeriaUser currentUser;

    private PurchaseService purchaseService;

    @BeforeEach
    void setUp() {
        purchaseService = new PurchaseService(purchaseRepository, userRepository);
        currentUser = new PizzeriaUser();
        currentUser.setName("Papa");
        currentUser.setId(666L);
        currentUser.setRoles(Collections.emptyList());
        currentUser.setEmail("abc@def.com");

        Authentication authentication = Mockito.mock(Authentication.class);
        SecurityContext securityContext = Mockito.mock(SecurityContext.class);
        Mockito.when(securityContext.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(securityContext);
        PizzeriaUserPrincipal userPrincipal = new PizzeriaUserPrincipal(currentUser);
        Mockito.when(authentication.getPrincipal()).thenReturn(userPrincipal);
    }

    @Test
    void should_create_draft_purchase_if_non_existing() {

        purchaseService.addPizzaToPurchase(new Pizza());

        ArgumentCaptor<Purchase> purchaseCaptor = ArgumentCaptor.forClass(Purchase.class);
        verify(purchaseRepository).save(purchaseCaptor.capture());
        Purchase saved = purchaseCaptor.getValue();
        assertThat(saved.getState()).isEqualByComparingTo(PurchaseState.DRAFT);
    }

    @Test
    void should_throw_exception_if_more_purchases() {
        when(purchaseRepository.findAllByStateEqualsAndCustomer_Id(any(), any()))
                .thenReturn(Arrays.asList(new Purchase(), new Purchase()));

        assertThrows(PizzeriaException.class, () -> purchaseService.addPizzaToPurchase(new Pizza()));
    }

    @Test
    void confirm_purchase_changes_state() {
        when(purchaseRepository.findAllByStateEqualsAndCustomer_Id(any(), any()))
                .thenReturn(Collections.singletonList(new Purchase()));

        purchaseService.confirmPurchase();

        ArgumentCaptor<Purchase> purchaseCaptor = ArgumentCaptor.forClass(Purchase.class);
        verify(purchaseRepository).save(purchaseCaptor.capture());
        Purchase saved = purchaseCaptor.getValue();
        assertThat(saved.getState()).isEqualByComparingTo(PurchaseState.PLACED);
    }

    @Test
    void should_add_items_to_purchase() {
        Pizza one = new Pizza();
        one.setId(666L);
        Pizza two = new Pizza();
        two.setId(43L);
        purchaseService.addPizzaToPurchase(one);
        purchaseService.addPizzaToPurchase(two);
        Purchase toReturn = new Purchase();
        toReturn.setPizzas(Arrays.asList(one, two));
        when(purchaseRepository.findAllByStateEqualsAndCustomer_Id(any(), any()))
                .thenReturn(Collections.singletonList(new Purchase()));
        when(purchaseRepository.findFirstByStateEquals(any())).thenReturn(new Purchase());
        when(purchaseRepository.save(any())).thenReturn(toReturn);

        purchaseService.confirmPurchase();

        Purchase latest = purchaseService.pickPurchase();
        assertThat(latest.getPizzas()).containsExactlyInAnyOrder(one, two);
    }

    @Test
    void confirm_pick_changes_state() {
        when(purchaseRepository.findFirstByStateEquals(any()))
                .thenReturn(new Purchase());

        purchaseService.pickPurchase();

        ArgumentCaptor<Purchase> purchaseCaptor = ArgumentCaptor.forClass(Purchase.class);
        verify(purchaseRepository).save(purchaseCaptor.capture());
        Purchase saved = purchaseCaptor.getValue();
        assertThat(saved.getState()).isEqualByComparingTo(PurchaseState.ONGOING);
    }

    @Test
    void confirm_close_changes_state() {
        Purchase p = new Purchase();
        p.setState(PurchaseState.ONGOING);
        when(purchaseRepository.findFirstByStateEquals(any()))
                .thenReturn(p);
        when(purchaseRepository.findById(any()))
                .thenReturn(Optional.of(p));
        purchaseService.pickPurchase();

        purchaseService.completePurchase(p.getId());

        ArgumentCaptor<Purchase> purchaseCaptor = ArgumentCaptor.forClass(Purchase.class);
        verify(purchaseRepository, times(2)).save(purchaseCaptor.capture());
        Purchase saved = purchaseCaptor.getValue();
        assertThat(saved.getState()).isEqualByComparingTo(PurchaseState.SERVED);
    }

    @Test
    void should_apply_10_percent_discount_on_orders_with_pineapple_toppings() {
        List<Pizza> orderedPizza = new ArrayList<>();
        orderedPizza.add(addPizza("pineapple", 10.00));
        orderedPizza.add(addPizza("mozzarella", 20.00));

        Double expectedDiscountedAmount = 28.0;

        Purchase p = new Purchase();
        p.setPizzas(orderedPizza);
        p.setState(PurchaseState.ONGOING);

        when(purchaseRepository.findFirstByStateEquals(any()))
                .thenReturn(p);
        when(purchaseRepository.findById(any()))
                .thenReturn(Optional.of(p));
        purchaseService.pickPurchase();

        purchaseService.completePurchase(p.getId());

        ArgumentCaptor<Purchase> purchaseCaptor = ArgumentCaptor.forClass(Purchase.class);
        verify(purchaseRepository, times(2)).save(purchaseCaptor.capture());
        Purchase saved = purchaseCaptor.getValue();
        assertThat(saved.getState()).isEqualByComparingTo(PurchaseState.SERVED);
        assertThat(saved.getAmount()).isEqualTo(expectedDiscountedAmount);
    }

    @Test
    void should_apply_100_percent_discount_on_the_cheapest_of_3_ordered_pizzas() {
        List<Pizza> orderedPizza = new ArrayList<>();
        orderedPizza.add(addPizza("margherita", 10.00));
        orderedPizza.add(addPizza("mozzarella", 20.00));
        orderedPizza.add(addPizza("pepperoni", 30.00));

        Double expectedDiscountedAmount = 50.0;

        Purchase p = new Purchase();
        p.setPizzas(orderedPizza);
        p.setState(PurchaseState.ONGOING);

        when(purchaseRepository.findFirstByStateEquals(any()))
                .thenReturn(p);
        when(purchaseRepository.findById(any()))
                .thenReturn(Optional.of(p));
        purchaseService.pickPurchase();

        purchaseService.completePurchase(p.getId());

        ArgumentCaptor<Purchase> purchaseCaptor = ArgumentCaptor.forClass(Purchase.class);
        verify(purchaseRepository, times(2)).save(purchaseCaptor.capture());
        Purchase saved = purchaseCaptor.getValue();
        assertThat(saved.getState()).isEqualByComparingTo(PurchaseState.SERVED);
        assertThat(saved.getAmount()).isEqualTo(expectedDiscountedAmount);
    }
    @Test
    void should_apply_100_percent_discount_on_the_cheapest_of_3_ordered_pizzas_and_override_previous_rule() {
        List<Pizza> orderedPizza = new ArrayList<>();
        orderedPizza.add(addPizza("pineapple", 10.00));
        orderedPizza.add(addPizza("mozzarella", 25.35));
        orderedPizza.add(addPizza("pepperoni", 30.35));

        Double expectedDiscountedAmount = 55.70;

        Purchase p = new Purchase();
        p.setPizzas(orderedPizza);
        p.setState(PurchaseState.ONGOING);

        when(purchaseRepository.findFirstByStateEquals(any()))
                .thenReturn(p);
        when(purchaseRepository.findById(any()))
                .thenReturn(Optional.of(p));
        purchaseService.pickPurchase();

        purchaseService.completePurchase(p.getId());

        ArgumentCaptor<Purchase> purchaseCaptor = ArgumentCaptor.forClass(Purchase.class);
        verify(purchaseRepository, times(2)).save(purchaseCaptor.capture());
        Purchase saved = purchaseCaptor.getValue();
        assertThat(saved.getState()).isEqualByComparingTo(PurchaseState.SERVED);
        assertThat(saved.getAmount()).isEqualTo(expectedDiscountedAmount);
    }

    @Test
    void should_apply_no_free_discount_on_orders_greater_than_3() {
        List<Pizza> orderedPizza = new ArrayList<>();
        orderedPizza.add(addPizza("mozzarella", 20.00));
        orderedPizza.add(addPizza("mozzarella", 20.00));
        orderedPizza.add(addPizza("mozzarella", 20.00));
        orderedPizza.add(addPizza("mozzarella", 20.00));

        Double expectedDiscountedAmount = 80.00;

        Purchase p = new Purchase();
        p.setPizzas(orderedPizza);
        p.setState(PurchaseState.ONGOING);

        when(purchaseRepository.findFirstByStateEquals(any()))
                .thenReturn(p);
        when(purchaseRepository.findById(any()))
                .thenReturn(Optional.of(p));
        purchaseService.pickPurchase();

        purchaseService.completePurchase(p.getId());

        ArgumentCaptor<Purchase> purchaseCaptor = ArgumentCaptor.forClass(Purchase.class);
        verify(purchaseRepository, times(2)).save(purchaseCaptor.capture());
        Purchase saved = purchaseCaptor.getValue();
        assertThat(saved.getState()).isEqualByComparingTo(PurchaseState.SERVED);
        assertThat(saved.getAmount()).isEqualTo(expectedDiscountedAmount);
    }

    private Pizza addPizza(String topping, Double price){
        Pizza pizza = new Pizza();
        pizza.setToppings(Arrays.asList(topping));
        pizza.setPrice(price);
        return pizza;
    }

}
