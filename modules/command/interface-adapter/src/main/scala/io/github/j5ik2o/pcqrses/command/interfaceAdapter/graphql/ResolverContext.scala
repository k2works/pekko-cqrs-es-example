package io.github.j5ik2o.pcqrses.command.interfaceAdapter.graphql

import io.github.j5ik2o.pcqrses.command.useCase.users.UserAccountUseCase
import io.github.j5ik2o.pcqrses.command.useCase.inventory.{
  ProductUseCase,
  InventoryUseCase,
  CustomerUseCase,
  WarehouseUseCase,
  WarehouseZoneUseCase
}
import zio.{Runtime, Task}

import scala.concurrent.{ExecutionContext, Future}

/**
 * GraphQLリゾルバー用のコンテキスト
 *
 * @param userAccountUseCase
 *   ユーザーアカウントのユースケース
 * @param productUseCase
 *   商品のユースケース
 * @param inventoryUseCase
 *   在庫のユースケース
 * @param customerUseCase
 *   取引先のユースケース
 * @param warehouseUseCase
 *   倉庫のユースケース
 * @param warehouseZoneUseCase
 *   倉庫ゾーンのユースケース
 * @param zioRuntime
 *   ZIOランタイム
 * @param ec
 *   ExecutionContext
 */
case class ResolverContext(
  userAccountUseCase: UserAccountUseCase,
  productUseCase: ProductUseCase,
  inventoryUseCase: InventoryUseCase,
  customerUseCase: CustomerUseCase,
  warehouseUseCase: WarehouseUseCase,
  warehouseZoneUseCase: WarehouseZoneUseCase,
  zioRuntime: Runtime[Any]
)(implicit ec: ExecutionContext) {

  /**
   * ZIO Taskを実行してFutureに変換
   */
  def runZioTask[A](task: Task[A]): Future[A] = {
    import zio.Unsafe
    Unsafe.unsafe { implicit u =>
      zioRuntime.unsafe.runToFuture(task)
    }
  }
}
