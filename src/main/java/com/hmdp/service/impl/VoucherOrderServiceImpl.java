package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.BeanUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static{
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    //代理对象
    @Resource
    private IVoucherOrderService proxy;


    @PostConstruct//启动时执行
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    @PreDestroy//销毁时执行
    public void destroy() {
        // 停止线程池，不再接收新任务
        SECKILL_ORDER_EXECUTOR.shutdown();
        try {
            // 等待正在执行的任务完成（例如等待几秒）
            if (!SECKILL_ORDER_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                SECKILL_ORDER_EXECUTOR.shutdownNow(); // 强制停止
            }
        } catch (InterruptedException e) {
            SECKILL_ORDER_EXECUTOR.shutdownNow();
        }
    }
    //阻塞队列（线程尝试访问这个队列中的元素时，只有当队列中有元素时这个线程才会被唤醒，否则这个线程就会被阻塞）
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            String queueName = "stream.orders";
            while(true){

                try {
                    //1、获取队列中的订单信息xreadgroup group g1 c1 count 1 block 2000 streams streams.order（消息队列名称） >（最近一条未消费消息）
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().
                            read(Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2000)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed()));
                    //2、判断消息是否获取成功
                    if(records.size() == 0 || records == null){
                        //2、1如果获取失败，说明没有消息，继续进行下一次循环
                        continue;
                    }
                    //3、解析消息中的订单信息
                    MapRecord<String, Object, Object> record = records.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    //4、获取成功可以下单
                    handleVoucherOrder(voucherOrder);
                    //5、ACK确认 sack stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());

                } catch (Exception e) {
                    handlePendingList();
                }
            }
        }

        /**
         * 处理pendingList中的消息
         */
        private void handlePendingList() {
            String queueName = "stream.orders";
            while(true){

                try {
                    //1、获取队列中的订单信息xreadgroup group g1 c1 count 1 streams streams.order（消息队列名称） *（未处理消息）
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().
                            read(Consumer.from("g1", "c1"),
                                    StreamReadOptions.empty().count(1),
                                    StreamOffset.create(queueName, ReadOffset.from("0")));
                    //2、判断消息是否获取成功
                    if(records.size() == 0 || records == null){
                        //2、1如果获取失败，说明没有消息，此时不需要处理pendinglist内处理异常的消息
                        break;
                    }
                    //3、解析消息中的订单信息
                    MapRecord<String, Object, Object> record = records.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //4、获取成功可以下单
                    handleVoucherOrder(voucherOrder);
                    //5、ACK确认 sack stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());

                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }
    }

    /**
     * 阻塞队列
     */
    /*
    阻塞队列
    //阻塞队列（线程尝试访问这个队列中的元素时，只有当队列中有元素时这个线程才会被唤醒，否则这个线程就会被阻塞）
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while(true){

                try {
                    //1、获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //2、创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }

            }
        }
    }*/
    /**
     * 秒杀优惠券
     * @param voucherId 优惠券id
     * @return 订单id
     */
    @Override
    public Result secKillVoucher(Long voucherId) throws InterruptedException {
        //获取用户id
        Long userId = UserHolder.getUser().getId();
        //获取订单id
        long orderId = redisIdWorker.nextId("order");
        //1、读取lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );
        int res = result.intValue();
        //2、判断结果是不是0
        if(res != 0){
            //2、1.不为0
            return Result.fail(res == 1 ? "库存不足！" : "不能重复下单！");
        }
        //3、返回订单id
        return Result.ok(orderId);
    }
   /* *//**
     * 秒杀优惠券
     * @param voucherId 优惠券id
     * @return 订单id
     *//*
    @Override
    public Result secKillVoucher(Long voucherId) throws InterruptedException {
        //获取用户id
        Long userId = UserHolder.getUser().getId();
        //1、读取lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );
        int res = result.intValue();
        //2、判断结果是不是0
        if(res != 0){
            //2、1.不为0
            return Result.fail(res == 1 ? "库存不足！" : "不能重复下单！");
        }
        //2、2.为0有购买资格，将订单信息保存在阻塞队列中
        long orderId = redisIdWorker.nextId("order");

        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        //2、3放入阻塞队列
        orderTasks.add(voucherOrder);
        //3、返回订单id
        return Result.ok(orderId);
    }*/

   /* *//**
     * 秒杀优惠券
     * @param voucherId 优惠券id
     * @return 订单id
     *//*
    @Override
    public Result secKillVoucher(Long voucherId) throws InterruptedException {

        //1、查询优惠券信息
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2、判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始！");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("活动已结束！");
        }
        //3、判断库存是否充足
        if (voucher.getStock()<1) {
            return Result.fail("库存不足！");
        }

        Long userId = UserHolder.getUser().getId();
        //8、返回订单id
        //获取锁对象
//        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId,stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁
        boolean isLock = lock.tryLock(1L, TimeUnit.SECONDS);
        if(!isLock){
            return Result.fail("不能重复下单！");
        }
        try {
            //获取代理对象，获取代理对象进行事务控制
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            //释放锁
            lock.unlock();
        }

    }*/

    /**
     * 创建订单
     * @param voucherOrder
     * @return
     */
    private void handleVoucherOrder(VoucherOrder voucherOrder) throws Exception {

        try {
            proxy.createVoucherOrder(voucherOrder);
        } catch (Exception e) {
            log.error("订单创建失败", e);
        }
    }

    @Transactional
    public  void createVoucherOrder(VoucherOrder voucherOrder) {
        //4、一人一单
        Long userId = voucherOrder.getUserId();
        //4、1.查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        //4、2判断是否存在
        if (count > 0) {
            return;
        }
        //5、扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1").gt("stock", 0)
                .eq("voucher_id", voucherOrder.getVoucherId()).update();
        if (!success) {
            return;
        }
        //7、订单写入数据库
        save(voucherOrder);
    }
}
